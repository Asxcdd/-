"""
مدير باقة الإنترنت - للكمبيوتر (Family Quota PC)
Companion Windows Desktop & Background Quota Manager sharing the exact same
Firebase Realtime Database (`users/{username}`) as the Android Family Quota app.
"""

from __future__ import annotations

import atexit
import base64
import ctypes
import hashlib
import hmac
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import customtkinter as ctk
import psutil
import pystray
import requests
from cryptography.fernet import Fernet, InvalidToken
from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Constants & Configuration
# ---------------------------------------------------------------------------
APP_TITLE = "مدير باقة الإنترنت - للكمبيوتر"
BYTES_PER_MB = 1024 * 1024
BYTES_PER_GB = 1024 * 1024 * 1024

DEMO_USERNAME = "test"
DEMO_PASSWORD = "123"
DEMO_QUOTA_LIMIT_MB = 50

# Firebase Realtime Database URL shared with the Android Family Quota app
DEFAULT_FIREBASE_DB_URL = "https://limit-app-fb76a-default-rtdb.europe-west1.firebasedatabase.app"

DATA_DIR = Path(r"C:\ProgramData\FamilyQuota") if os.name == "nt" else Path.home() / ".family_quota"
STATE_FILE = DATA_DIR / "state.dat"

FIREWALL_BLOCK_RULE = "FamilyQuotaBlock"
FIREWALL_ALLOW_SELF_RULE = "FamilyQuotaAllowSelf"
FIREWALL_ALLOW_DNS_RULE = "FamilyQuotaAllowDns"
STARTUP_TASK_NAME = "FamilyQuotaPC"

POLL_ADMIN_INTERVAL_SEC = 10
SYNC_FIREBASE_INTERVAL_SEC = 30
TRAFFIC_SAMPLE_INTERVAL_SEC = 2

USERNAME_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{2,32}$")
IGNORED_NIC_KEYWORDS = (
    "loopback",
    "pseudo",
    "vethernet",
    "vmware",
    "virtualbox",
    "vbox",
    "docker",
    "isatap",
    "teredo",
    "hyper-v",
    "wsl",
)

# Color Palette ("Midnight Shield" matching the Android app)
BG_DARK = "#070B14"
CARD_DARK = "#0F172A"
SURFACE_DARK = "#162033"
CYAN_ACCENT = "#06B6D4"
EMERALD_ACCENT = "#10B981"
AMBER_ACCENT = "#F59E0B"
CORAL_ACCENT = "#EF4444"
TEXT_PRIMARY = "#F8FAFC"
TEXT_MUTED = "#94A3B8"


# ---------------------------------------------------------------------------
# Data Models
# ---------------------------------------------------------------------------
@dataclass
class QuotaState:
    is_logged_in: bool = False
    username: str = ""
    is_demo: bool = False
    firebase_url: str = DEFAULT_FIREBASE_DB_URL
    quota_limit_mb: int = 0
    synced_used_mb: int = 0
    pending_bytes: int = 0
    simulated_extra_bytes: int = 0
    is_active: bool = False
    last_nic_total_bytes: int = -1
    last_sync_epoch: float = 0.0
    is_blocking: bool = True
    status_message: str = "في انتظار تسجيل الدخول"

    @property
    def effective_used_bytes(self) -> int:
        return max(0, (self.synced_used_mb * BYTES_PER_MB) + self.pending_bytes + self.simulated_extra_bytes)

    @property
    def effective_used_mb(self) -> int:
        return self.effective_used_bytes // BYTES_PER_MB

    @property
    def quota_limit_bytes(self) -> int:
        return max(0, self.quota_limit_mb) * BYTES_PER_MB

    @property
    def remaining_bytes(self) -> int:
        return max(0, self.quota_limit_bytes - self.effective_used_bytes)

    @property
    def should_block(self) -> bool:
        if not self.is_logged_in or not self.is_active:
            return True
        return self.effective_used_bytes >= self.quota_limit_bytes

    @property
    def usage_fraction(self) -> float:
        limit = self.quota_limit_bytes
        if limit <= 0:
            return 1.0
        return min(1.0, max(0.0, self.effective_used_bytes / float(limit)))


def format_bytes_ar(num_bytes: int) -> str:
    safe = max(0, num_bytes)
    if safe >= BYTES_PER_GB:
        return f"{safe / BYTES_PER_GB:.2f} جيجابايت"
    if safe >= BYTES_PER_MB:
        return f"{safe / BYTES_PER_MB:.1f} ميجابايت"
    if safe >= 1024:
        return f"{safe / 1024:.0f} كيلوبايت"
    return f"{safe} بايت"


def format_mb_ar(mb: int) -> str:
    safe = max(0, mb)
    if safe >= 1024:
        return f"{safe / 1024:.2f} جيجابايت"
    return f"{safe} ميجابايت"


# ---------------------------------------------------------------------------
# Windows Privilege, Encrypted Storage & Startup Helpers
# ---------------------------------------------------------------------------
def is_windows() -> bool:
    return os.name == "nt"


def is_admin() -> bool:
    if not is_windows():
        return True
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_as_admin() -> None:
    if not is_windows():
        return
    try:
        if getattr(sys, "frozen", False):
            executable = sys.executable
            params = " ".join(f'"{arg}"' for arg in sys.argv[1:])
        else:
            executable = sys.executable
            script = os.path.abspath(sys.argv[0])
            extra = " ".join(f'"{arg}"' for arg in sys.argv[1:])
            params = f'"{script}" {extra}'.strip()

        ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", executable, params, None, 1)
        if ret > 32:
            sys.exit(0)
    except Exception:
        pass


def get_executable_path() -> str:
    if getattr(sys, "frozen", False):
        return os.path.abspath(sys.executable)
    return os.path.abspath(sys.executable)


def get_startup_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{os.path.abspath(sys.executable)}"'
    script = os.path.abspath(sys.argv[0])
    return f'"{os.path.abspath(sys.executable)}" "{script}"'


def _set_hidden_attribute(path: Path) -> None:
    if not is_windows():
        return
    try:
        # FILE_ATTRIBUTE_HIDDEN (0x02) | FILE_ATTRIBUTE_SYSTEM (0x04)
        ctypes.windll.kernel32.SetFileAttributesW(str(path), 0x02 | 0x04)
    except Exception:
        pass


def _derive_machine_fernet() -> Fernet:
    machine_id = "family-quota-default-machine"
    if is_windows():
        try:
            import winreg
            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"SOFTWARE\Microsoft\Cryptography",
                0,
                winreg.KEY_READ,
            ) as key:
                val, _ = winreg.QueryValueEx(key, "MachineGuid")
                if val:
                    machine_id = str(val)
        except Exception:
            machine_id = os.environ.get("COMPUTERNAME", machine_id)
    digest = hashlib.sha256(f"{machine_id}::FamilyQuotaPC_v1".encode("utf-8")).digest()
    return Fernet(base64.urlsafe_b64encode(digest))


class EncryptedConfigStore:
    def __init__(self) -> None:
        self._fernet = _derive_machine_fernet()

    def load(self) -> QuotaState:
        if not STATE_FILE.exists():
            return QuotaState()
        try:
            encrypted = STATE_FILE.read_bytes()
            raw = self._fernet.decrypt(encrypted).decode("utf-8")
            data = json.loads(raw)
            return QuotaState(
                is_logged_in=bool(data.get("is_logged_in", False)),
                username=str(data.get("username", "")),
                is_demo=bool(data.get("is_demo", False)),
                firebase_url=str(data.get("firebase_url", DEFAULT_FIREBASE_DB_URL)),
                quota_limit_mb=max(0, int(data.get("quota_limit_mb", 0))),
                synced_used_mb=max(0, int(data.get("synced_used_mb", 0))),
                pending_bytes=max(0, int(data.get("pending_bytes", 0))),
                simulated_extra_bytes=max(0, int(data.get("simulated_extra_bytes", 0))),
                is_active=bool(data.get("is_active", False)),
                last_nic_total_bytes=int(data.get("last_nic_total_bytes", -1)),
                last_sync_epoch=float(data.get("last_sync_epoch", 0.0)),
            )
        except (InvalidToken, json.JSONDecodeError, OSError, ValueError):
            return QuotaState()

    def save(self, state: QuotaState) -> None:
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            _set_hidden_attribute(DATA_DIR)
            payload = {
                "is_logged_in": state.is_logged_in,
                "username": state.username,
                "is_demo": state.is_demo,
                "firebase_url": state.firebase_url,
                "quota_limit_mb": state.quota_limit_mb,
                "synced_used_mb": state.synced_used_mb,
                "pending_bytes": state.pending_bytes,
                "simulated_extra_bytes": state.simulated_extra_bytes,
                "is_active": state.is_active,
                "last_nic_total_bytes": state.last_nic_total_bytes,
                "last_sync_epoch": state.last_sync_epoch,
            }
            encrypted = self._fernet.encrypt(json.dumps(payload).encode("utf-8"))
            STATE_FILE.write_bytes(encrypted)
            _set_hidden_attribute(STATE_FILE)
        except Exception:
            pass


def register_windows_startup() -> bool:
    """Register auto-start with highest (Admin) privileges via Windows Task Scheduler."""
    if not is_windows():
        return False
    creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    cmd = [
        "schtasks",
        "/Create",
        "/TN",
        STARTUP_TASK_NAME,
        "/TR",
        get_startup_command(),
        "/SC",
        "ONLOGON",
        "/RL",
        "HIGHEST",
        "/F",
    ]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            creationflags=creation_flags,
            timeout=10,
        )
        if result.returncode == 0:
            return True
    except Exception:
        pass

    # Fallback: Registry Run key
    try:
        import winreg
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0,
            winreg.KEY_SET_VALUE,
        ) as key:
            winreg.SetValueEx(key, STARTUP_TASK_NAME, 0, winreg.REG_SZ, get_startup_command())
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Windows Firewall Controller (No Speed Loss in Allow State)
# ---------------------------------------------------------------------------
class WindowsFirewallManager:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._currently_blocking: Optional[bool] = None
        self._creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if is_windows() else 0

    def _run_netsh(self, args: list[str]) -> bool:
        if not is_windows():
            return True
        try:
            res = subprocess.run(
                ["netsh", "advfirewall", "firewall", *args],
                capture_output=True,
                text=True,
                creationflags=self._creation_flags,
                timeout=10,
            )
            return res.returncode == 0
        except Exception:
            return False

    def apply_allow_state(self, force: bool = False) -> None:
        with self._lock:
            if not force and self._currently_blocking is False:
                return
            self._run_netsh(["delete", "rule", f"name={FIREWALL_BLOCK_RULE}"])
            self._run_netsh(["delete", "rule", f"name={FIREWALL_ALLOW_SELF_RULE}"])
            self._run_netsh(["delete", "rule", f"name={FIREWALL_ALLOW_DNS_RULE}"])
            self._run_netsh(["set", "allprofiles", "firewallpolicy", "blockinbound,allowoutbound"])
            self._currently_blocking = False

    def apply_block_state(self, force: bool = False) -> None:
        with self._lock:
            if not force and self._currently_blocking is True:
                return
            exe_path = get_executable_path()
            self._run_netsh(["delete", "rule", f"name={FIREWALL_BLOCK_RULE}"])
            self._run_netsh(["delete", "rule", f"name={FIREWALL_ALLOW_SELF_RULE}"])
            self._run_netsh(["delete", "rule", f"name={FIREWALL_ALLOW_DNS_RULE}"])

            # Allow ONLY our own executable + system DNS lookup so Firebase polling stays alive
            self._run_netsh([
                "add", "rule",
                f"name={FIREWALL_ALLOW_SELF_RULE}",
                "dir=out",
                "action=allow",
                f"program={exe_path}",
                "enable=yes",
                "profile=any",
            ])
            self._run_netsh([
                "add", "rule",
                f"name={FIREWALL_ALLOW_DNS_RULE}",
                "dir=out",
                "action=allow",
                "protocol=UDP",
                "remoteport=53",
                "service=Dnscache",
                "enable=yes",
                "profile=any",
            ])
            # Block all other outbound traffic at native Windows Firewall level
            self._run_netsh(["set", "allprofiles", "firewallpolicy", "blockinbound,blockoutbound"])
            self._currently_blocking = True

    def safe_restore_on_exit(self) -> None:
        self.apply_allow_state(force=True)


# ---------------------------------------------------------------------------
# Network Traffic Monitor (psutil)
# ---------------------------------------------------------------------------
def get_active_physical_nic_bytes() -> int:
    """Return total bytes sent + received across active non-loopback physical NICs."""
    try:
        counters = psutil.net_io_counters(pernic=True)
        stats = psutil.net_if_stats()
    except Exception:
        return -1

    total = 0
    for nic_name, io in counters.items():
        lowered = nic_name.lower()
        if any(k in lowered for k in IGNORED_NIC_KEYWORDS):
            continue
        nic_stat = stats.get(nic_name)
        if nic_stat is not None and not nic_stat.isup:
            continue
        total += max(0, io.bytes_sent) + max(0, io.bytes_recv)
    return total


# ---------------------------------------------------------------------------
# Firebase Realtime Database REST Client
# ---------------------------------------------------------------------------
class FirebaseRestClient:
    @staticmethod
    def normalize_url(raw_url: str) -> Optional[str]:
        cleaned = raw_url.strip().rstrip("/")
        parsed = urllib.parse.urlparse(cleaned)
        if parsed.scheme != "https" or not parsed.netloc:
            return None
        if "YOUR-PROJECT-ID" in cleaned:
            return None
        return cleaned

    @staticmethod
    def is_valid_username(username: str) -> bool:
        return bool(USERNAME_PATTERN.match(username.strip()))

    def authenticate(self, firebase_url: str, raw_username: str, raw_password: str) -> tuple[bool, dict | str]:
        username = raw_username.strip()
        if not self.is_valid_username(username):
            return False, "اسم المستخدم يجب أن يكون من ٢ إلى ٣٢ حرفاً (أحرف إنجليزية أو أرقام أو _ أو -)."
        if not raw_password:
            return False, "يرجى إدخال كلمة المرور."

        if username == DEMO_USERNAME:
            if hmac.compare_digest(DEMO_PASSWORD.encode("utf-8"), raw_password.encode("utf-8")):
                return True, {
                    "username": DEMO_USERNAME,
                    "quota_limit_mb": DEMO_QUOTA_LIMIT_MB,
                    "used_mb": 0,
                    "is_active": True,
                    "is_demo": True,
                }
            return False, "اسم المستخدم أو كلمة المرور غير صحيحة."

        base = self.normalize_url(firebase_url)
        if not base:
            return False, "يرجى إدخال رابط Firebase Realtime Database الصحيح (https://...)."

        try:
            resp = requests.get(f"{base}/users/{username}.json", timeout=8)
            if resp.status_code != 200:
                return False, f"تعذر الاتصال بـ Firebase (رمز {resp.status_code})."
            data = resp.json()
            if not isinstance(data, dict):
                return False, "اسم المستخدم أو كلمة المرور غير صحيحة."
            stored_pw = str(data.get("password", ""))
            if not stored_pw or not hmac.compare_digest(stored_pw.encode("utf-8"), raw_password.encode("utf-8")):
                return False, "اسم المستخدم أو كلمة المرور غير صحيحة."

            return True, {
                "username": username,
                "quota_limit_mb": max(0, int(data.get("quota_limit_mb", 0))),
                "used_mb": max(0, int(data.get("used_mb", 0))),
                "is_active": bool(data.get("is_active", False)),
                "is_demo": False,
            }
        except requests.RequestException as exc:
            return False, f"خطأ في الاتصال بالإنترنت أو Firebase: {exc}"
        except Exception as exc:
            return False, f"خطأ غير متوقع: {exc}"

    def fetch_user(self, firebase_url: str, username: str) -> Optional[dict]:
        base = self.normalize_url(firebase_url)
        if not base or not self.is_valid_username(username):
            return None
        try:
            resp = requests.get(f"{base}/users/{username}.json", timeout=8)
            if resp.status_code != 200:
                return None
            data = resp.json()
            if not isinstance(data, dict):
                return None
            return {
                "quota_limit_mb": max(0, int(data.get("quota_limit_mb", 0))),
                "used_mb": max(0, int(data.get("used_mb", 0))),
                "is_active": bool(data.get("is_active", False)),
            }
        except Exception:
            return None

    def patch_used_mb(self, firebase_url: str, username: str, used_mb: int) -> bool:
        base = self.normalize_url(firebase_url)
        if not base or not self.is_valid_username(username):
            return False
        try:
            resp = requests.patch(
                f"{base}/users/{username}.json",
                json={"used_mb": max(0, int(used_mb))},
                timeout=8,
            )
            return 200 <= resp.status_code < 300
        except Exception:
            return False


# ---------------------------------------------------------------------------
# Main Application Controller & Arabic RTL UI
# ---------------------------------------------------------------------------
class FamilyQuotaDesktopApp:
    def __init__(self) -> None:
        self.store = EncryptedConfigStore()
        self.firewall = WindowsFirewallManager()
        self.firebase = FirebaseRestClient()
        self.state_lock = threading.Lock()
        self.state: QuotaState = self.store.load()
        self.stop_event = threading.Event()
        self.tray_icon: Optional[pystray.Icon] = None

        # Restore NIC baseline to avoid counting negative/huge delta after reboot
        current_nic = get_active_physical_nic_bytes()
        if current_nic >= 0:
            self.state.last_nic_total_bytes = current_nic

        # Enforce initial firewall state immediately on boot/launch
        if self.state.should_block:
            self.firewall.apply_block_state(force=True)
            self.state.is_blocking = True
        else:
            self.firewall.apply_allow_state(force=True)
            self.state.is_blocking = False

        atexit.register(self._on_process_exit)
        signal.signal(signal.SIGTERM, lambda *_: self.quit_app())

        # Setup CustomTkinter
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")
        self.root = ctk.CTk()
        self.root.title(APP_TITLE)
        self.root.geometry("500x680")
        self.root.minsize(460, 620)
        self.root.configure(fg_color=BG_DARK)
        self.root.protocol("WM_DELETE_WINDOW", self.minimize_to_tray)

        self.container = ctk.CTkFrame(self.root, fg_color=BG_DARK)
        self.container.pack(fill="both", expand=True, padx=18, pady=18)

        self._render_current_screen()
        self._start_background_workers()
        self._start_system_tray()
        self.root.after(1000, self._ui_refresh_tick)

    # -----------------------------------------------------------------------
    # Background Workers (Traffic, Admin Poll 10s, Sync 30s)
    # -----------------------------------------------------------------------
    def _start_background_workers(self) -> None:
        threading.Thread(target=self._traffic_worker, daemon=True).start()
        threading.Thread(target=self._admin_poll_worker, daemon=True).start()
        threading.Thread(target=self._sync_worker, daemon=True).start()

    def _traffic_worker(self) -> None:
        while not self.stop_event.is_set():
            self.stop_event.wait(TRAFFIC_SAMPLE_INTERVAL_SEC)
            if self.stop_event.is_set():
                break

            reached_limit_now = False
            with self.state_lock:
                current_nic = get_active_physical_nic_bytes()
                if current_nic >= 0:
                    if self.state.last_nic_total_bytes >= 0 and current_nic >= self.state.last_nic_total_bytes:
                        delta = current_nic - self.state.last_nic_total_bytes
                    else:
                        # Reboot or adapter reset occurred
                        delta = 0
                    self.state.last_nic_total_bytes = current_nic

                    if self.state.is_logged_in and self.state.is_active and not self.state.is_blocking:
                        prev_bytes = self.state.effective_used_bytes
                        self.state.pending_bytes += delta
                        if prev_bytes < self.state.quota_limit_bytes <= self.state.effective_used_bytes:
                            reached_limit_now = True

                self._apply_firewall_locked()
                self.store.save(self.state)

            if reached_limit_now:
                self._sync_once()

    def _admin_poll_worker(self) -> None:
        while not self.stop_event.is_set():
            self.stop_event.wait(POLL_ADMIN_INTERVAL_SEC)
            if self.stop_event.is_set():
                break

            with self.state_lock:
                if not self.state.is_logged_in or self.state.is_demo:
                    continue
                url = self.state.firebase_url
                username = self.state.username

            remote = self.firebase.fetch_user(url, username)
            if remote is None:
                continue

            with self.state_lock:
                if not self.state.is_logged_in or self.state.username != username:
                    continue
                prev_synced = self.state.synced_used_mb
                remote_used = remote["used_mb"]
                self.state.quota_limit_mb = remote["quota_limit_mb"]
                self.state.is_active = remote["is_active"]

                # Detect admin reset (used_mb lowered/cleared to 0)
                if remote_used == 0 and prev_synced > 0:
                    self.state.synced_used_mb = 0
                    self.state.pending_bytes = 0
                    self.state.status_message = "تمت إعادة تعيين الباقة من الإدارة"
                elif remote_used > self.state.effective_used_mb:
                    self.state.synced_used_mb = remote_used
                    self.state.pending_bytes = 0

                self._apply_firewall_locked()
                self.store.save(self.state)

    def _sync_worker(self) -> None:
        while not self.stop_event.is_set():
            self.stop_event.wait(SYNC_FIREBASE_INTERVAL_SEC)
            if self.stop_event.is_set():
                break
            self._sync_once()

    def _sync_once(self) -> None:
        with self.state_lock:
            if not self.state.is_logged_in:
                return
            if self.state.is_demo:
                self.state.last_sync_epoch = time.time()
                self.store.save(self.state)
                return
            url = self.state.firebase_url
            username = self.state.username
            used_mb = self.state.effective_used_mb
            remainder = self.state.pending_bytes % BYTES_PER_MB

        if self.firebase.patch_used_mb(url, username, used_mb):
            with self.state_lock:
                if self.state.username == username:
                    self.state.synced_used_mb = used_mb
                    self.state.pending_bytes = remainder
                    self.state.last_sync_epoch = time.time()
                    self.state.status_message = f"تمت المزامنة ({used_mb} ميجابايت)"
                    self.store.save(self.state)

    def _apply_firewall_locked(self) -> None:
        if self.state.should_block:
            self.firewall.apply_block_state()
            self.state.is_blocking = True
        else:
            self.firewall.apply_allow_state()
            self.state.is_blocking = False

    # -----------------------------------------------------------------------
    # UI Rendering (Arabic RTL)
    # -----------------------------------------------------------------------
    def _clear_container(self) -> None:
        for child in self.container.winfo_children():
            child.destroy()

    def _render_current_screen(self) -> None:
        self._clear_container()
        with self.state_lock:
            logged_in = self.state.is_logged_in

        if logged_in:
            self._build_dashboard_ui()
        else:
            self._build_login_ui()

    def _build_login_ui(self) -> None:
        header = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=18)
        header.pack(fill="x", pady=(0, 14))

        ctk.CTkLabel(
            header,
            text="مدير باقة الإنترنت - للكمبيوتر",
            font=("Tahoma", 20, "bold"),
            text_color=CYAN_ACCENT,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=18, pady=(16, 4))

        ctk.CTkLabel(
            header,
            text="حماية فورية عبر جدار حماية ويندوز ومزامنة مباشرة مع Firebase",
            font=("Tahoma", 12),
            text_color=TEXT_MUTED,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=18, pady=(0, 16))

        # Demo Hint Card
        demo_card = ctk.CTkFrame(self.container, fg_color=SURFACE_DARK, corner_radius=14)
        demo_card.pack(fill="x", pady=(0, 14))

        demo_row = ctk.CTkFrame(demo_card, fg_color="transparent")
        demo_row.pack(fill="x", padx=14, pady=12)

        ctk.CTkButton(
            demo_row,
            text="تعبئة التجربة",
            width=105,
            height=34,
            fg_color=CYAN_ACCENT,
            text_color="#070B14",
            font=("Tahoma", 12, "bold"),
            command=self._fill_demo_credentials,
        ).pack(side="left")

        ctk.CTkLabel(
            demo_row,
            text="حساب تجريبي: test / 123 (50 ميجابايت)",
            font=("Tahoma", 12, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
            justify="right",
        ).pack(side="right", fill="x", expand=True)

        # Login Form Card
        form = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=18)
        form.pack(fill="both", expand=True)

        ctk.CTkLabel(
            form,
            text="تسجيل الدخول",
            font=("Tahoma", 18, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=20, pady=(18, 12))

        ctk.CTkLabel(
            form,
            text="رابط Firebase Realtime Database (اتركه للحساب التجريبي)",
            font=("Tahoma", 11),
            text_color=TEXT_MUTED,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.firebase_url_entry = ctk.CTkEntry(
            form,
            height=38,
            fg_color=SURFACE_DARK,
            border_color="#334155",
            justify="left",
        )
        self.firebase_url_entry.insert(0, self.state.firebase_url)
        self.firebase_url_entry.pack(fill="x", padx=20, pady=(4, 12))

        ctk.CTkLabel(
            form,
            text="اسم المستخدم",
            font=("Tahoma", 13, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.username_entry = ctk.CTkEntry(
            form,
            height=42,
            placeholder_text="test أو alex",
            fg_color=SURFACE_DARK,
            border_color="#334155",
            justify="right",
        )
        self.username_entry.pack(fill="x", padx=20, pady=(4, 12))

        ctk.CTkLabel(
            form,
            text="كلمة المرور",
            font=("Tahoma", 13, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.password_entry = ctk.CTkEntry(
            form,
            height=42,
            show="*",
            fg_color=SURFACE_DARK,
            border_color="#334155",
            justify="right",
        )
        self.password_entry.pack(fill="x", padx=20, pady=(4, 12))
        self.password_entry.bind("<Return>", lambda _e: self._handle_login())

        self.login_error_label = ctk.CTkLabel(
            form,
            text="",
            font=("Tahoma", 12),
            text_color=CORAL_ACCENT,
            anchor="e",
            justify="right",
            wraplength=410,
        )
        self.login_error_label.pack(fill="x", padx=20, pady=(0, 8))

        self.login_btn = ctk.CTkButton(
            form,
            text="دخول",
            height=46,
            corner_radius=12,
            fg_color=CYAN_ACCENT,
            hover_color="#0891B2",
            text_color="#070B14",
            font=("Tahoma", 15, "bold"),
            command=self._handle_login,
        )
        self.login_btn.pack(fill="x", padx=20, pady=(4, 18))

    def _fill_demo_credentials(self) -> None:
        self.username_entry.delete(0, "end")
        self.username_entry.insert(0, DEMO_USERNAME)
        self.password_entry.delete(0, "end")
        self.password_entry.insert(0, DEMO_PASSWORD)
        self.login_error_label.configure(text="")

    def _handle_login(self) -> None:
        username = self.username_entry.get().strip()
        password = self.password_entry.get()
        fb_url = self.firebase_url_entry.get().strip()

        self.login_btn.configure(state="disabled", text="جاري التحقق...")
        self.login_error_label.configure(text="")

        def worker() -> None:
            ok, payload = self.firebase.authenticate(fb_url, username, password)
            self.root.after(0, lambda: self._on_login_result(ok, payload, fb_url))

        threading.Thread(target=worker, daemon=True).start()

    def _on_login_result(self, ok: bool, payload: dict | str, fb_url: str) -> None:
        if not ok:
            self.login_btn.configure(state="normal", text="دخول")
            self.login_error_label.configure(text=str(payload))
            return

        assert isinstance(payload, dict)
        nic_now = get_active_physical_nic_bytes()
        with self.state_lock:
            self.state.is_logged_in = True
            self.state.username = payload["username"]
            self.state.is_demo = bool(payload["is_demo"])
            self.state.firebase_url = fb_url or DEFAULT_FIREBASE_DB_URL
            self.state.quota_limit_mb = int(payload["quota_limit_mb"])
            self.state.synced_used_mb = int(payload["used_mb"])
            self.state.pending_bytes = 0
            self.state.simulated_extra_bytes = 0
            self.state.is_active = bool(payload["is_active"])
            self.state.last_nic_total_bytes = nic_now
            self.state.last_sync_epoch = time.time()
            self.state.status_message = (
                "حساب تجريبي نشط" if self.state.is_demo else "متصل بـ Firebase"
            )
            self._apply_firewall_locked()
            self.store.save(self.state)

        self._render_current_screen()

    def _build_dashboard_ui(self) -> None:
        # Top Bar (RTL: logout on left, user info on right)
        top_bar = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=16)
        top_bar.pack(fill="x", pady=(0, 14))

        top_inner = ctk.CTkFrame(top_bar, fg_color="transparent")
        top_inner.pack(fill="x", padx=16, pady=12)

        ctk.CTkButton(
            top_inner,
            text="تسجيل الخروج",
            width=115,
            height=36,
            fg_color="#1E293B",
            hover_color="#334155",
            text_color=TEXT_PRIMARY,
            font=("Tahoma", 12, "bold"),
            command=self._handle_logout,
        ).pack(side="left")

        user_title = (
            f"{self.state.username} (حساب تجريبي)"
            if self.state.is_demo
            else self.state.username
        )
        ctk.CTkLabel(
            top_inner,
            text=user_title,
            font=("Tahoma", 15, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        ).pack(side="right")

        # Main Status & Progress Card
        hero = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=20)
        hero.pack(fill="x", pady=(0, 14))

        self.conn_status_label = ctk.CTkLabel(
            hero,
            text="حالة الإنترنت: يعمل",
            font=("Tahoma", 15, "bold"),
            text_color=EMERALD_ACCENT,
            anchor="e",
            justify="right",
        )
        self.conn_status_label.pack(fill="x", padx=20, pady=(18, 6))

        ctk.CTkLabel(
            hero,
            text="الباقة المتبقية",
            font=("Tahoma", 13),
            text_color=TEXT_MUTED,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.remaining_big_label = ctk.CTkLabel(
            hero,
            text="0 ميجابايت",
            font=("Tahoma", 28, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        )
        self.remaining_big_label.pack(fill="x", padx=20, pady=(2, 10))

        self.progress_bar = ctk.CTkProgressBar(
            hero,
            height=14,
            corner_radius=8,
            progress_color=CYAN_ACCENT,
            fg_color=SURFACE_DARK,
        )
        self.progress_bar.pack(fill="x", padx=20, pady=(0, 14))

        # 3-Column RTL Metrics Grid (Right -> Left: Remaining, Used, Limit)
        metrics = ctk.CTkFrame(hero, fg_color="transparent")
        metrics.pack(fill="x", padx=16, pady=(0, 18))
        metrics.grid_columnconfigure((0, 1, 2), weight=1)

        self.limit_tile_val = self._create_metric_tile(metrics, 0, "الحد الأقصى للباقة")
        self.used_tile_val = self._create_metric_tile(metrics, 1, "الاستهلاك الحالي")
        self.remain_tile_val = self._create_metric_tile(metrics, 2, "الباقة المتبقية")

        # Demo Controls Card (ONLY for test user)
        if self.state.is_demo:
            demo_panel = ctk.CTkFrame(self.container, fg_color=SURFACE_DARK, corner_radius=16)
            demo_panel.pack(fill="x", pady=(0, 14))

            ctk.CTkLabel(
                demo_panel,
                text="اختبار الحساب التجريبي (50 ميجابايت)",
                font=("Tahoma", 13, "bold"),
                text_color=CYAN_ACCENT,
                anchor="e",
            ).pack(fill="x", padx=16, pady=(12, 8))

            self.demo_action_btn = ctk.CTkButton(
                demo_panel,
                text="محاكاة انتهاء الباقة",
                height=42,
                corner_radius=12,
                fg_color=CORAL_ACCENT,
                hover_color="#DC2626",
                font=("Tahoma", 13, "bold"),
                command=self._toggle_demo_block,
            )
            self.demo_action_btn.pack(fill="x", padx=16, pady=(0, 14))
        else:
            self.demo_action_btn = None

        # System Info Card
        info_card = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=18)
        info_card.pack(fill="both", expand=True)

        self.firewall_info_label = ctk.CTkLabel(
            info_card,
            text="",
            font=("Tahoma", 12),
            text_color=TEXT_PRIMARY,
            anchor="e",
            justify="right",
            wraplength=420,
        )
        self.firewall_info_label.pack(fill="x", padx=18, pady=(16, 8))

        self.sync_info_label = ctk.CTkLabel(
            info_card,
            text="",
            font=("Tahoma", 12),
            text_color=TEXT_MUTED,
            anchor="e",
            justify="right",
            wraplength=420,
        )
        self.sync_info_label.pack(fill="x", padx=18, pady=(0, 14))

        ctk.CTkButton(
            info_card,
            text="إخفاء في شريط المهام (System Tray)",
            height=38,
            fg_color=SURFACE_DARK,
            hover_color="#1E293B",
            font=("Tahoma", 12),
            command=self.minimize_to_tray,
        ).pack(fill="x", padx=18, pady=(0, 16))

        self._refresh_dashboard_labels()

    def _create_metric_tile(self, parent: ctk.CTkFrame, col: int, title: str) -> ctk.CTkLabel:
        tile = ctk.CTkFrame(parent, fg_color=SURFACE_DARK, corner_radius=14)
        tile.grid(row=0, column=col, sticky="nsew", padx=4)
        ctk.CTkLabel(
            tile,
            text=title,
            font=("Tahoma", 11),
            text_color=TEXT_MUTED,
            anchor="center",
        ).pack(padx=6, pady=(10, 4))
        val_label = ctk.CTkLabel(
            tile,
            text="0",
            font=("Tahoma", 13, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="center",
        )
        val_label.pack(padx=6, pady=(0, 10))
        return val_label

    def _ui_refresh_tick(self) -> None:
        if not self.stop_event.is_set():
            with self.state_lock:
                logged_in = self.state.is_logged_in
            if logged_in and hasattr(self, "conn_status_label"):
                self._refresh_dashboard_labels()
            self.root.after(1000, self._ui_refresh_tick)

    def _refresh_dashboard_labels(self) -> None:
        with self.state_lock:
            snap = QuotaState(
                is_logged_in=self.state.is_logged_in,
                username=self.state.username,
                is_demo=self.state.is_demo,
                firebase_url=self.state.firebase_url,
                quota_limit_mb=self.state.quota_limit_mb,
                synced_used_mb=self.state.synced_used_mb,
                pending_bytes=self.state.pending_bytes,
                simulated_extra_bytes=self.state.simulated_extra_bytes,
                is_active=self.state.is_active,
                is_blocking=self.state.is_blocking,
                last_sync_epoch=self.state.last_sync_epoch,
                status_message=self.state.status_message,
            )

        if snap.should_block:
            self.conn_status_label.configure(
                text="حالة الإنترنت: مقطوع (انتهت الباقة)",
                text_color=CORAL_ACCENT,
            )
            self.progress_bar.configure(progress_color=CORAL_ACCENT)
            self.remaining_big_label.configure(text="0 ميجابايت")
        else:
            color = AMBER_ACCENT if snap.usage_fraction >= 0.8 else EMERALD_ACCENT
            self.conn_status_label.configure(
                text="حالة الإنترنت: يعمل",
                text_color=color,
            )
            self.progress_bar.configure(progress_color=CYAN_ACCENT if snap.usage_fraction < 0.8 else AMBER_ACCENT)
            self.remaining_big_label.configure(text=format_bytes_ar(snap.remaining_bytes))

        self.progress_bar.set(snap.usage_fraction)
        self.used_tile_val.configure(text=format_bytes_ar(snap.effective_used_bytes))
        self.limit_tile_val.configure(text=format_mb_ar(snap.quota_limit_mb))
        self.remain_tile_val.configure(text=format_bytes_ar(snap.remaining_bytes))

        fw_text = (
            "جدار الحماية: مفعّل (حظر الاتصال مع السماح فقط بمزامنة البرنامج)"
            if snap.is_blocking
            else "جدار الحماية: مفتوح (الإنترنت يعمل بالسرعة الكاملة 100%)"
        )
        self.firewall_info_label.configure(text=fw_text)

        sync_time = (
            time.strftime("%H:%M:%S", time.localtime(snap.last_sync_epoch))
            if snap.last_sync_epoch > 0
            else "قيد الانتظار"
        )
        self.sync_info_label.configure(
            text=f"الحالة: {snap.status_message} • آخر تحديث: {sync_time}"
        )

        if self.demo_action_btn is not None:
            if snap.should_block:
                self.demo_action_btn.configure(
                    text="إعادة تعيين الباقة التجريبية (50 ميجابايت)",
                    fg_color=EMERALD_ACCENT,
                    hover_color="#059669",
                )
            else:
                self.demo_action_btn.configure(
                    text="محاكاة انتهاء الباقة",
                    fg_color=CORAL_ACCENT,
                    hover_color="#DC2626",
                )

    def _toggle_demo_block(self) -> None:
        with self.state_lock:
            if not self.state.is_demo:
                return
            if self.state.should_block:
                self.state.synced_used_mb = 0
                self.state.pending_bytes = 0
                self.state.simulated_extra_bytes = 0
                self.state.is_active = True
                self.state.status_message = "تمت إعادة تعيين الباقة التجريبية"
            else:
                self.state.simulated_extra_bytes = self.state.quota_limit_mb * BYTES_PER_MB
                self.state.status_message = "محاكاة انتهاء الباقة نشطة"
            self._apply_firewall_locked()
            self.store.save(self.state)
        self._refresh_dashboard_labels()

    def _handle_logout(self) -> None:
        with self.state_lock:
            fb_url = self.state.firebase_url
            nic_now = get_active_physical_nic_bytes()
            self.state = QuotaState(
                is_logged_in=False,
                firebase_url=fb_url,
                last_nic_total_bytes=nic_now,
                status_message="تم تسجيل الخروج - الإنترنت محظور",
            )
            self._apply_firewall_locked()
            self.store.save(self.state)
        self._render_current_screen()

    # -----------------------------------------------------------------------
    # System Tray & Lifecycle
    # -----------------------------------------------------------------------
    def _create_tray_image(self) -> Image.Image:
        img = Image.new("RGB", (64, 64), color="#0F172A")
        draw = ImageDraw.Draw(img)
        draw.ellipse((10, 10, 54, 54), outline="#06B6D4", width=6)
        draw.ellipse((22, 22, 42, 42), fill="#10B981")
        return img

    def _start_system_tray(self) -> None:
        menu = pystray.Menu(
            pystray.MenuItem("فتح مدير الباقة", lambda _icon, _item: self.root.after(0, self.restore_from_tray)),
            pystray.MenuItem("إغلاق آمن", lambda _icon, _item: self.root.after(0, self.quit_app)),
        )
        self.tray_icon = pystray.Icon("FamilyQuotaPC", self._create_tray_image(), APP_TITLE, menu)
        threading.Thread(target=self.tray_icon.run, daemon=True).start()

    def minimize_to_tray(self) -> None:
        self.root.withdraw()

    def restore_from_tray(self) -> None:
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def _on_process_exit(self) -> None:
        self.stop_event.set()
        self.firewall.safe_restore_on_exit()

    def quit_app(self) -> None:
        self.stop_event.set()
        self.firewall.safe_restore_on_exit()
        if self.tray_icon is not None:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        try:
            self.root.destroy()
        except Exception:
            pass

    def run(self) -> None:
        self.root.mainloop()


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------
def main() -> None:
    if is_windows() and not is_admin():
        relaunch_as_admin()
        return

    register_windows_startup()
    app = FamilyQuotaDesktopApp()
    app.run()


if __name__ == "__main__":
    main()
