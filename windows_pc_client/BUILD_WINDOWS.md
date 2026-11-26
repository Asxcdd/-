# Family Quota PC (`FamilyQuotaPC.exe`) — Build Instructions

## 1. Install Dependencies (Windows 10 / 11)
```powershell
cd windows_pc_client
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## 2. Firebase Realtime Database URL
`main.py` is already configured with `https://limit-app-fb76a-default-rtdb.europe-west1.firebasedatabase.app` (and can also be adjusted from the Login screen if needed).

> You can also test immediately offline using the built-in Demo Account:
> - **Username:** `test`
> - **Password:** `123`
> - **Quota:** `50` MB

## 3. Build Single-File `.exe` with Admin UAC Manifest
Run PyInstaller with `--uac-admin` so Windows prompts for Administrator privileges automatically on launch:

```powershell
pyinstaller --noconsole --onefile --uac-admin --name FamilyQuotaPC --collect-all customtkinter main.py
```

The standalone executable will be output at:
`dist\FamilyQuotaPC.exe`
