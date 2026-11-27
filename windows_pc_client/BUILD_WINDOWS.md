# Family Quota PC (`FamilyQuotaPC.exe`) — Production Build Instructions

## 1. Install Dependencies (Windows 10 / 11)
```powershell
cd windows_pc_client
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## 2. Production Firebase Configuration
`main.py` is hardcoded to your production Firebase Realtime Database:
`https://limit-app-fb76a-default-rtdb.europe-west1.firebasedatabase.app`

## 3. Build Single-File `.exe` with Admin UAC Manifest
Run PyInstaller with `--uac-admin` so Windows prompts for Administrator privileges automatically on launch:

```powershell
pyinstaller --noconsole --onefile --uac-admin --name FamilyQuotaPC --collect-all customtkinter main.py
```

The standalone executable will be output at:
`dist\FamilyQuotaPC.exe`
