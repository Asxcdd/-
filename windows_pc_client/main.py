"""
جيجاتك - مدير باقة الإنترنت للكمبيوتر (Gigatak PC)
Production Windows Desktop & Background Quota Manager sharing the exact same
Firebase Realtime Database (`users/{username}`) as the Android جيجاتك app.
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
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import customtkinter as ctk
import psutil
import pystray
import requests
from cryptography.fernet import Fernet, InvalidToken
from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Production Constants
# ---------------------------------------------------------------------------
APP_TITLE = "جيجاتك"
BYTES_PER_MB = 1024 * 1024
BYTES_PER_GB = 1024 * 1024 * 1024

FIREBASE_DATABASE_URL = "https://limit-app-fb76a-default-rtdb.europe-west1.firebasedatabase.app"

DATA_DIR = Path(r"C:\ProgramData\FamilyQuota") if os.name == "nt" else Path.home() / ".family_quota"
STATE_FILE = DATA_DIR / "state.dat"

RULE_BLOCK_ALL = "FamilyQuota_BlockAll"
RULE_BLOCK_ALL_UDP = "FamilyQuota_BlockAll_UDP"
RULE_ALLOW_APP = "FamilyQuota_AllowApp"
RULE_ALLOW_DNS = "FamilyQuota_AllowDNS"
RULE_ALLOW_DNS_TCP = "FamilyQuota_AllowDNS_TCP"
STARTUP_TASK_NAME = "FamilyQuotaPC"

MONITOR_SYNC_INTERVAL_SEC = 10

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

# Midnight Shield Palette
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
    quota_limit_mb: int = 0
    used_mb: float = 0.0
    last_synced_used_mb: float = 0.0
    is_active: bool = False
    last_nic_total_bytes: int = -1
    last_sync_epoch: float = 0.0
    is_blocking: bool = True
    suspended_fw_rules: list[str] = field(default_factory=list)
    status_message: str = "في انتظار تسجيل الدخول"

    @property
    def rounded_used_mb(self) -> float:
        return round(max(0.0, self.used_mb), 2)

    @property
    def effective_used_bytes(self) -> int:
        return int(max(0.0, self.used_mb) * BYTES_PER_MB)

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
        if self.quota_limit_mb <= 0:
            return True
        return self.rounded_used_mb >= float(self.quota_limit_mb)

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
        return f"{safe / BYTES_PER_MB:.2f} ميجابايت"
    if safe >= 1024:
        return f"{safe / 1024:.0f} كيلوبايت"
    return f"{safe} بايت"


def format_mb_ar(mb: int | float) -> str:
    safe = max(0.0, float(mb))
    if safe >= 1024.0:
        return f"{safe / 1024.0:.2f} جيجابايت"
    if isinstance(mb, int):
        return f"{int(safe)} ميجابايت"
    return f"{safe:.2f} ميجابايت"


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
        executable = os.path.abspath(sys.executable)
        if getattr(sys, "frozen", False):
            params = " ".join(f'"{arg}"' for arg in sys.argv[1:])
        else:
            script = os.path.abspath(sys.argv[0])
            extra = " ".join(f'"{arg}"' for arg in sys.argv[1:])
            params = f'"{script}" {extra}'.strip()

        ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", executable, params, None, 1)
        if ret > 32:
            sys.exit(0)
    except Exception:
        pass


def get_executable_path() -> str:
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
            suspended = data.get("suspended_fw_rules", [])
            if not isinstance(suspended, list):
                suspended = []

            # Support both new float `used_mb` and legacy `synced_used_mb` + `pending_bytes`
            if "used_mb" in data:
                loaded_used_mb = max(0.0, float(data.get("used_mb", 0.0)))
            else:
                legacy_mb = max(0, int(data.get("synced_used_mb", 0)))
                legacy_pending = max(0, int(data.get("pending_bytes", 0)))
                loaded_used_mb = float(legacy_mb) + (legacy_pending / float(BYTES_PER_MB))

            return QuotaState(
                is_logged_in=bool(data.get("is_logged_in", False)),
                username=str(data.get("username", "")),
                quota_limit_mb=max(0, int(data.get("quota_limit_mb", 0))),
                used_mb=loaded_used_mb,
                last_synced_used_mb=round(max(0.0, float(data.get("last_synced_used_mb", loaded_used_mb))), 2),
                is_active=bool(data.get("is_active", False)),
                last_nic_total_bytes=int(data.get("last_nic_total_bytes", -1)),
                last_sync_epoch=float(data.get("last_sync_epoch", 0.0)),
                suspended_fw_rules=[str(r) for r in suspended if isinstance(r, str)],
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
                "quota_limit_mb": state.quota_limit_mb,
                "used_mb": state.used_mb,
                "last_synced_used_mb": state.last_synced_used_mb,
                "is_active": state.is_active,
                "last_nic_total_bytes": state.last_nic_total_bytes,
                "last_sync_epoch": state.last_sync_epoch,
                "suspended_fw_rules": state.suspended_fw_rules,
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
            shell=False,
            capture_output=True,
            text=True,
            creationflags=creation_flags,
            timeout=10,
        )
        if result.returncode == 0:
            return True
    except Exception:
        pass

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
# Windows Firewall Controller (Overrides Existing App Allow Rules)
# ---------------------------------------------------------------------------
class WindowsFirewallManager:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._currently_blocking: Optional[bool] = None
        self._creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if is_windows() else 0

    def _run_cmd(self, cmd: list[str], timeout: int = 15) -> subprocess.CompletedProcess:
        return subprocess.run(
            cmd,
            shell=False,
            capture_output=True,
            text=True,
            creationflags=self._creation_flags,
            timeout=timeout,
        )

    def _run_netsh(self, args: list[str]) -> bool:
        if not is_windows():
            return True
        try:
            res = self._run_cmd(["netsh", *args])
            return res.returncode == 0
        except Exception:
            return False

    def _delete_app_rules(self) -> None:
        for rule_name in (
            RULE_BLOCK_ALL,
            RULE_BLOCK_ALL_UDP,
            RULE_ALLOW_APP,
            RULE_ALLOW_DNS,
            RULE_ALLOW_DNS_TCP,
        ):
            self._run_netsh(["advfirewall", "firewall", "delete", "rule", f"name={rule_name}"])

    def _suspend_existing_outbound_allow_rules(self, existing_suspended: list[str]) -> list[str]:
        """Disable pre-existing enabled outbound Allow rules (Chrome, Edge, Discord, games)
        so blockinbound,blockoutbound cuts them off cleanly while preserving Firebase access."""
        if not is_windows():
            return existing_suspended
        ps_script = (
            "$rules = Get-NetFirewallRule -Direction Outbound -Action Allow -Enabled True "
            "-ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike 'FamilyQuota_*' }; "
            "$names = @($rules | ForEach-Object { $_.Name }); "
            "if ($names.Count -gt 0) { $rules | Disable-NetFirewallRule -ErrorAction SilentlyContinue }; "
            "$names | ConvertTo-Json -Compress"
        )
        try:
            res = self._run_cmd(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps_script],
                timeout=20,
            )
            stdout = (res.stdout or "").strip()
            newly_suspended: list[str] = []
            if stdout:
                parsed = json.loads(stdout)
                if isinstance(parsed, str):
                    newly_suspended = [parsed]
                elif isinstance(parsed, list):
                    newly_suspended = [str(x) for x in parsed if isinstance(x, str)]
            merged = list(dict.fromkeys([*existing_suspended, *newly_suspended]))
            return merged
        except Exception:
            return existing_suspended

    def _restore_suspended_outbound_allow_rules(self, suspended_rules: list[str]) -> list[str]:
        if not is_windows() or not suspended_rules:
            return []
        try:
            encoded_json = json.dumps(suspended_rules)
            ps_script = (
                f"$names = '{encoded_json.replace(chr(39), chr(39) + chr(39))}' | ConvertFrom-Json; "
                "foreach ($n in $names) { Enable-NetFirewallRule -Name $n -ErrorAction SilentlyContinue }"
            )
            self._run_cmd(
                ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps_script],
                timeout=20,
            )
            return []
        except Exception:
            return suspended_rules

    def block_internet(self, suspended_rules: list[str], force: bool = False) -> list[str]:
        with self._lock:
            if not force and self._currently_blocking is True:
                return suspended_rules

            exe_path = get_executable_path()
            svchost_path = os.path.join(
                os.environ.get("SystemRoot", r"C:\Windows"),
                "System32",
                "svchost.exe",
            )

            # 1. Remove any stale FamilyQuota rules
            self._delete_app_rules()

            # 2. Suspend pre-existing outbound Allow rules (Chrome, Edge, Discord, games)
            updated_suspended = self._suspend_existing_outbound_allow_rules(suspended_rules)

            # 3. Enforce strict default outbound block across all firewall profiles
            self._run_netsh(["advfirewall", "set", "allprofiles", "firewallpolicy", "blockinbound,blockoutbound"])

            # 4. Explicit high-priority block rules for all other outbound ports (blocks any remaining apps/games)
            self._run_netsh([
                "advfirewall", "firewall", "add", "rule",
                f"name={RULE_BLOCK_ALL}",
                "dir=out",
                "action=block",
                "protocol=TCP",
                "remoteport=1-52,54-442,444-65535",
                "profile=any",
                "enable=yes",
            ])
            self._run_netsh([
                "advfirewall", "firewall", "add", "rule",
                f"name={RULE_BLOCK_ALL_UDP}",
                "dir=out",
                "action=block",
                "protocol=UDP",
                "remoteport=1-52,54-65535",
                "profile=any",
                "enable=yes",
            ])

            # 5. Explicit allow rules ONLY for our .exe and DNS (UDP/TCP 53 for svchost.exe)
            self._run_netsh([
                "advfirewall", "firewall", "add", "rule",
                f"name={RULE_ALLOW_APP}",
                "dir=out",
                "action=allow",
                f"program={exe_path}",
                "profile=any",
                "enable=yes",
            ])
            self._run_netsh([
                "advfirewall", "firewall", "add", "rule",
                f"name={RULE_ALLOW_DNS}",
                "dir=out",
                "action=allow",
                f"program={svchost_path}",
                "protocol=UDP",
                "remoteport=53",
                "profile=any",
                "enable=yes",
            ])
            self._run_netsh([
                "advfirewall", "firewall", "add", "rule",
                f"name={RULE_ALLOW_DNS_TCP}",
                "dir=out",
                "action=allow",
                f"program={svchost_path}",
                "protocol=TCP",
                "remoteport=53",
                "profile=any",
                "enable=yes",
            ])

            self._currently_blocking = True
            return updated_suspended

    def unblock_internet(self, suspended_rules: list[str], force: bool = False) -> list[str]:
        with self._lock:
            if not force and self._currently_blocking is False:
                return suspended_rules

            # 1. Delete all FamilyQuota block & allow rules
            self._delete_app_rules()

            # 2. Re-enable any suspended pre-existing Allow rules
            remaining = self._restore_suspended_outbound_allow_rules(suspended_rules)

            # 3. Restore normal Windows Firewall outbound policy
            self._run_netsh(["advfirewall", "set", "allprofiles", "firewallpolicy", "blockinbound,allowoutbound"])

            self._currently_blocking = False
            return remaining


# ---------------------------------------------------------------------------
# Network Traffic Monitor (psutil)
# ---------------------------------------------------------------------------
def get_active_physical_nic_bytes() -> int:
    """Return total bytes_sent + bytes_recv across all active non-loopback NICs."""
    try:
        counters = psutil.net_io_counters(pernic=True)
        stats = psutil.net_if_stats()
    except Exception:
        counters = {}
        stats = {}

    total = 0
    matched_any = False
    for nic_name, io in counters.items():
        lowered = nic_name.lower()
        if any(k in lowered for k in IGNORED_NIC_KEYWORDS):
            continue
        nic_stat = stats.get(nic_name)
        if nic_stat is not None and not nic_stat.isup:
            continue
        matched_any = True
        total += max(0, io.bytes_sent) + max(0, io.bytes_recv)

    if not matched_any or total == 0:
        try:
            agg = psutil.net_io_counters(pernic=False)
            if agg is not None:
                return max(0, agg.bytes_sent) + max(0, agg.bytes_recv)
        except Exception:
            return -1
    return total


# ---------------------------------------------------------------------------
# Firebase Realtime Database REST Client (Hardcoded Production URL)
# ---------------------------------------------------------------------------
class FirebaseRestClient:
    def __init__(self, database_url: str = FIREBASE_DATABASE_URL) -> None:
        self._base_url = database_url.strip().rstrip("/")

    @staticmethod
    def is_valid_username(username: str) -> bool:
        return bool(USERNAME_PATTERN.match(username.strip()))

    def authenticate(self, raw_username: str, raw_password: str) -> tuple[bool, dict | str]:
        username = raw_username.strip()
        if not self.is_valid_username(username):
            return False, "اسم المستخدم يجب أن يكون من ٢ إلى ٣٢ حرفاً (أحرف إنجليزية أو أرقام أو _ أو -)."
        if not raw_password:
            return False, "يرجى إدخال كلمة المرور."

        try:
            resp = requests.get(f"{self._base_url}/users/{username}.json", timeout=8)
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
                "quota_limit_mb": max(0, int(float(data.get("quota_limit_mb", 0)))),
                "used_mb": round(max(0.0, float(data.get("used_mb", 0.0))), 2),
                "is_active": bool(data.get("is_active", False)),
            }
        except requests.RequestException as exc:
            return False, f"خطأ في الاتصال بالإنترنت أو Firebase: {exc}"
        except Exception as exc:
            return False, f"خطأ غير متوقع: {exc}"

    def fetch_user(self, username: str) -> Optional[dict]:
        if not self.is_valid_username(username):
            return None
        try:
            resp = requests.get(f"{self._base_url}/users/{username}.json", timeout=8)
            if resp.status_code != 200:
                return None
            data = resp.json()
            if not isinstance(data, dict):
                return None
            return {
                "quota_limit_mb": max(0, int(float(data.get("quota_limit_mb", 0)))),
                "used_mb": round(max(0.0, float(data.get("used_mb", 0.0))), 2),
                "is_active": bool(data.get("is_active", False)),
            }
        except Exception:
            return None

    def patch_used_mb(self, username: str, used_mb: float) -> bool:
        if not self.is_valid_username(username):
            return False
        try:
            resp = requests.patch(
                f"{self._base_url}/users/{username}.json",
                json={"used_mb": round(max(0.0, float(used_mb)), 2)},
                timeout=8,
            )
            return 200 <= resp.status_code < 300
        except Exception:
            return False


# ---------------------------------------------------------------------------
# Main Application Controller & Arabic RTL UI
# ---------------------------------------------------------------------------
class GigatakDesktopApp:
    def __init__(self) -> None:
        self.store = EncryptedConfigStore()
        self.firewall = WindowsFirewallManager()
        self.firebase = FirebaseRestClient(FIREBASE_DATABASE_URL)
        self.state_lock = threading.Lock()
        self.state: QuotaState = self.store.load()
        self.stop_event = threading.Event()
        self._session_stop_event: Optional[threading.Event] = None
        self.tray_icon: Optional[pystray.Icon] = None

        # Restore NIC baseline to avoid counting deltas across reboots
        current_nic = get_active_physical_nic_bytes()
        if current_nic >= 0:
            self.state.last_nic_total_bytes = current_nic

        # Enforce internet block IMMEDIATELY on startup if logged out or quota unavailable
        if self.state.should_block:
            self.state.suspended_fw_rules = self.firewall.block_internet(
                self.state.suspended_fw_rules, force=True
            )
            self.state.is_blocking = True
        else:
            self.state.suspended_fw_rules = self.firewall.unblock_internet(
                self.state.suspended_fw_rules, force=True
            )
            self.state.is_blocking = False
        self.store.save(self.state)

        atexit.register(self._on_process_exit)
        signal.signal(signal.SIGTERM, lambda *_: self.quit_app())

        # Setup CustomTkinter Arabic RTL GUI
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")
        self.root = ctk.CTk()
        self.root.title(APP_TITLE)
        self.root.geometry("500x640")
        self.root.minsize(460, 580)
        self.root.configure(fg_color=BG_DARK)
        self.root.protocol("WM_DELETE_WINDOW", self.minimize_to_tray)

        self.container = ctk.CTkFrame(self.root, fg_color=BG_DARK)
        self.container.pack(fill="both", expand=True, padx=18, pady=18)

        self._render_current_screen()
        self._start_system_tray()

        if self.state.is_logged_in:
            self._start_session_monitor_thread()

        self.root.after(1000, self._ui_refresh_tick)

    # -----------------------------------------------------------------------
    # Dedicated 10-Second Usage Tracking & Firebase Sync Daemon Thread
    # -----------------------------------------------------------------------
    def _stop_session_monitor_thread(self) -> None:
        if self._session_stop_event is not None:
            self._session_stop_event.set()
            self._session_stop_event = None

    def _start_session_monitor_thread(self) -> None:
        self._stop_session_monitor_thread()
        session_stop = threading.Event()
        self._session_stop_event = session_stop
        threading.Thread(
            target=self._session_usage_sync_worker,
            args=(session_stop,),
            daemon=True,
        ).start()

    def _session_usage_sync_worker(self, session_stop: threading.Event) -> None:
        while not self.stop_event.is_set() and not session_stop.is_set():
            session_stop.wait(MONITOR_SYNC_INTERVAL_SEC)
            if self.stop_event.is_set() or session_stop.is_set():
                break

            # 1-3 & 5. Sample psutil NIC bytes, calculate delta, update used_mb, enforce firewall
            with self.state_lock:
                if not self.state.is_logged_in:
                    continue
                username = self.state.username
                current_bytes = get_active_physical_nic_bytes()
                if current_bytes >= 0:
                    if (
                        self.state.last_nic_total_bytes >= 0
                        and current_bytes >= self.state.last_nic_total_bytes
                    ):
                        delta_bytes = current_bytes - self.state.last_nic_total_bytes
                    else:
                        delta_bytes = 0
                    self.state.last_nic_total_bytes = current_bytes

                    if self.state.is_active and not self.state.is_blocking:
                        self.state.used_mb = max(
                            0.0,
                            self.state.used_mb + (delta_bytes / float(BYTES_PER_MB)),
                        )

                used_mb_to_patch = self.state.rounded_used_mb
                self._apply_firewall_locked()
                self.store.save(self.state)

            # 4a. Update UI immediately
            self._schedule_ui_refresh()

            # 4b. Send PATCH request to Firebase with {"used_mb": round(used_mb, 2)}
            patch_ok = self.firebase.patch_used_mb(username, used_mb_to_patch)
            if patch_ok:
                with self.state_lock:
                    if self.state.is_logged_in and self.state.username == username:
                        self.state.last_synced_used_mb = used_mb_to_patch
                        self.state.last_sync_epoch = time.time()
                        self.state.status_message = f"تمت المزامنة ({used_mb_to_patch:.2f} ميجابايت)"
                        self.store.save(self.state)
            else:
                with self.state_lock:
                    if self.state.is_logged_in and self.state.username == username:
                        self.state.status_message = "جاري حفظ الاستهلاك محلياً..."
                        self.store.save(self.state)

            # 5. Fetch real-time admin updates (quota_limit_mb / is_active / quota reset) & enforce block immediately
            remote = self.firebase.fetch_user(username)
            if remote is not None:
                with self.state_lock:
                    if self.state.is_logged_in and self.state.username == username:
                        prev_synced = self.state.last_synced_used_mb
                        remote_used = float(remote["used_mb"])
                        self.state.quota_limit_mb = int(remote["quota_limit_mb"])
                        self.state.is_active = bool(remote["is_active"])

                        if remote_used <= 0.0 and prev_synced > 0.0:
                            self.state.used_mb = 0.0
                            self.state.last_synced_used_mb = 0.0
                            self.state.status_message = "تمت إعادة تعيين الباقة من الإدارة"
                        elif remote_used > self.state.rounded_used_mb:
                            self.state.used_mb = remote_used
                            self.state.last_synced_used_mb = remote_used

                        self._apply_firewall_locked()
                        self.store.save(self.state)

            self._schedule_ui_refresh()

    def _schedule_ui_refresh(self) -> None:
        try:
            self.root.after(0, self._safe_refresh_dashboard)
        except Exception:
            pass

    def _safe_refresh_dashboard(self) -> None:
        with self.state_lock:
            logged_in = self.state.is_logged_in
        if logged_in and hasattr(self, "conn_status_label"):
            self._refresh_dashboard_labels()

    def _apply_firewall_locked(self) -> None:
        if self.state.should_block:
            self.state.suspended_fw_rules = self.firewall.block_internet(
                self.state.suspended_fw_rules
            )
            self.state.is_blocking = True
        else:
            self.state.suspended_fw_rules = self.firewall.unblock_internet(
                self.state.suspended_fw_rules
            )
            self.state.is_blocking = False

    # -----------------------------------------------------------------------
    # UI Rendering (Arabic RTL — Username & Password only)
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
        header.pack(fill="x", pady=(0, 16))

        ctk.CTkLabel(
            header,
            text="جيجاتك",
            font=("Tahoma", 22, "bold"),
            text_color=CYAN_ACCENT,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=18, pady=(18, 4))

        ctk.CTkLabel(
            header,
            text="حماية فورية عبر جدار حماية ويندوز ومزامنة مباشرة للباقة",
            font=("Tahoma", 12),
            text_color=TEXT_MUTED,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=18, pady=(0, 18))

        # Login Form Card (Username & Password only)
        form = ctk.CTkFrame(self.container, fg_color=CARD_DARK, corner_radius=18)
        form.pack(fill="both", expand=True)

        ctk.CTkLabel(
            form,
            text="تسجيل الدخول",
            font=("Tahoma", 18, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
            justify="right",
        ).pack(fill="x", padx=20, pady=(22, 16))

        ctk.CTkLabel(
            form,
            text="اسم المستخدم",
            font=("Tahoma", 13, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.username_entry = ctk.CTkEntry(
            form,
            height=44,
            placeholder_text="أدخل اسم المستخدم",
            fg_color=SURFACE_DARK,
            border_color="#334155",
            justify="right",
        )
        self.username_entry.pack(fill="x", padx=20, pady=(6, 16))

        ctk.CTkLabel(
            form,
            text="كلمة المرور",
            font=("Tahoma", 13, "bold"),
            text_color=TEXT_PRIMARY,
            anchor="e",
        ).pack(fill="x", padx=20)

        self.password_entry = ctk.CTkEntry(
            form,
            height=44,
            show="*",
            placeholder_text="أدخل كلمة المرور",
            fg_color=SURFACE_DARK,
            border_color="#334155",
            justify="right",
        )
        self.password_entry.pack(fill="x", padx=20, pady=(6, 14))
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
        self.login_error_label.pack(fill="x", padx=20, pady=(0, 10))

        self.login_btn = ctk.CTkButton(
            form,
            text="دخول",
            height=48,
            corner_radius=12,
            fg_color=CYAN_ACCENT,
            hover_color="#0891B2",
            text_color="#070B14",
            font=("Tahoma", 15, "bold"),
            command=self._handle_login,
        )
        self.login_btn.pack(fill="x", padx=20, pady=(4, 22))

    def _handle_login(self) -> None:
        username = self.username_entry.get().strip()
        password = self.password_entry.get()

        self.login_btn.configure(state="disabled", text="جاري التحقق...")
        self.login_error_label.configure(text="")

        def worker() -> None:
            ok, payload = self.firebase.authenticate(username, password)
            self.root.after(0, lambda: self._on_login_result(ok, payload))

        threading.Thread(target=worker, daemon=True).start()

    def _on_login_result(self, ok: bool, payload: dict | str) -> None:
        if not ok:
            self.login_btn.configure(state="normal", text="دخول")
            self.login_error_label.configure(text=str(payload))
            return

        assert isinstance(payload, dict)
        nic_now = get_active_physical_nic_bytes()
        remote_used_mb = float(payload["used_mb"])
        with self.state_lock:
            self.state.is_logged_in = True
            self.state.username = payload["username"]
            self.state.quota_limit_mb = int(payload["quota_limit_mb"])
            self.state.used_mb = remote_used_mb
            self.state.last_synced_used_mb = round(remote_used_mb, 2)
            self.state.is_active = bool(payload["is_active"])
            self.state.last_nic_total_bytes = nic_now
            self.state.last_sync_epoch = time.time()
            self.state.status_message = "متصل بـ Firebase"
            self._apply_firewall_locked()
            self.store.save(self.state)

        self._render_current_screen()
        self._start_session_monitor_thread()

    def _build_dashboard_ui(self) -> None:
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

        ctk.CTkLabel(
            top_inner,
            text=f"جيجاتك • {self.state.username}",
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

        # 3-Column RTL Metrics Grid
        metrics = ctk.CTkFrame(hero, fg_color="transparent")
        metrics.pack(fill="x", padx=16, pady=(0, 18))
        metrics.grid_columnconfigure((0, 1, 2), weight=1)

        self.limit_tile_val = self._create_metric_tile(metrics, 0, "الحد الأقصى للباقة")
        self.used_tile_val = self._create_metric_tile(metrics, 1, "الاستهلاك الحالي")
        self.remain_tile_val = self._create_metric_tile(metrics, 2, "الباقة المتبقية")

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
        self.firewall_info_label.pack(fill="x", padx=18, pady=(18, 8))

        self.sync_info_label = ctk.CTkLabel(
            info_card,
            text="",
            font=("Tahoma", 12),
            text_color=TEXT_MUTED,
            anchor="e",
            justify="right",
            wraplength=420,
        )
        self.sync_info_label.pack(fill="x", padx=18, pady=(0, 18))

        ctk.CTkButton(
            info_card,
            text="إخفاء في شريط المهام (System Tray)",
            height=40,
            fg_color=SURFACE_DARK,
            hover_color="#1E293B",
            font=("Tahoma", 12),
            command=self.minimize_to_tray,
        ).pack(fill="x", padx=18, pady=(0, 18))

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
            self._safe_refresh_dashboard()
            self.root.after(1000, self._ui_refresh_tick)

    def _refresh_dashboard_labels(self) -> None:
        with self.state_lock:
            snap = QuotaState(
                is_logged_in=self.state.is_logged_in,
                username=self.state.username,
                quota_limit_mb=self.state.quota_limit_mb,
                used_mb=self.state.used_mb,
                last_synced_used_mb=self.state.last_synced_used_mb,
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
            self.progress_bar.configure(
                progress_color=CYAN_ACCENT if snap.usage_fraction < 0.8 else AMBER_ACCENT
            )
            self.remaining_big_label.configure(text=format_bytes_ar(snap.remaining_bytes))

        self.progress_bar.set(snap.usage_fraction)
        self.used_tile_val.configure(text=format_bytes_ar(snap.effective_used_bytes))
        self.limit_tile_val.configure(text=format_mb_ar(snap.quota_limit_mb))
        self.remain_tile_val.configure(text=format_bytes_ar(snap.remaining_bytes))

        fw_text = (
            "جدار الحماية: مفعّل (تم حظر جميع المتصفحات والتطبيقات مع إبقاء مزامنة الباقة فقط)"
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

    def _handle_logout(self) -> None:
        self._stop_session_monitor_thread()
        with self.state_lock:
            nic_now = get_active_physical_nic_bytes()
            suspended = self.state.suspended_fw_rules
            self.state = QuotaState(
                is_logged_in=False,
                last_nic_total_bytes=nic_now,
                suspended_fw_rules=suspended,
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
            pystray.MenuItem("فتح جيجاتك", lambda _icon, _item: self.root.after(0, self.restore_from_tray)),
            pystray.MenuItem("إغلاق آمن", lambda _icon, _item: self.root.after(0, self.quit_app)),
        )
        self.tray_icon = pystray.Icon("GigatakPC", self._create_tray_image(), APP_TITLE, menu)
        threading.Thread(target=self.tray_icon.run, daemon=True).start()

    def minimize_to_tray(self) -> None:
        self.root.withdraw()

    def restore_from_tray(self) -> None:
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def _on_process_exit(self) -> None:
        self.stop_event.set()
        self._stop_session_monitor_thread()
        with self.state_lock:
            self.state.suspended_fw_rules = self.firewall.unblock_internet(
                self.state.suspended_fw_rules, force=True
            )
            self.state.is_blocking = False
            self.store.save(self.state)

    def quit_app(self) -> None:
        self.stop_event.set()
        self._stop_session_monitor_thread()
        with self.state_lock:
            self.state.suspended_fw_rules = self.firewall.unblock_internet(
                self.state.suspended_fw_rules, force=True
            )
            self.state.is_blocking = False
            self.store.save(self.state)
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
    app = GigatakDesktopApp()
    app.run()


if __name__ == "__main__":
    main()
