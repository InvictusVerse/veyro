; Veyro for Windows installer (ADR-075). Built by build-installer.ps1, which passes:
;   VERSION, MSIX, DEPENDENCY, CERT, THUMBPRINT, OUTFILE, ICON
; Veyro is an MSIX app (startup task, firewall rule, package identity), so this wraps the
; signed MSIX instead of copying files: it trusts Veyro's self-signed publisher certificate,
; installs the Windows App Runtime and Veyro for the current user, and adds an "Installed apps"
; entry whose uninstaller removes all of it again. One administrator prompt.

Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"

!define APPNAME "Veyro"
!define PFN "Veyro.Windows_crr6skddq09z2"
!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Veyro"

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$PROGRAMFILES64\Veyro"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
BrandingText "Veyro ${VERSION}"
VIProductVersion "${VERSION}"
VIAddVersionKey "ProductName" "Veyro"
VIAddVersionKey "FileDescription" "Veyro setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "Veyro"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Open Veyro"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchVeyro

!define MUI_WELCOMEPAGE_TEXT "This installs Veyro ${VERSION}: your Android phone's notifications, messages, calls, files, screen and camera on this PC.$\r$\n$\r$\nVeyro needs Windows 11. Setup also trusts Veyro's publisher certificate on this PC, so Windows accepts the app."
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function .onInit
  ; Windows 11 is build 22000 or later.
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\Windows NT\CurrentVersion" "CurrentBuildNumber"
  ${If} $0 < 22000
    MessageBox MB_ICONSTOP "Veyro needs Windows 11."
    Abort
  ${EndIf}
FunctionEnd

Function LaunchVeyro
  Exec '"$WINDIR\explorer.exe" shell:AppsFolder\${PFN}!App'
FunctionEnd

; Runs one PowerShell command; $0 = exit code, $1 = output.
!macro PS CMD
  nsExec::ExecToStack 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "${CMD}"'
  Pop $0
  Pop $1
!macroend

Section "Install"
  SetOutPath "$PLUGINSDIR"
  File "/oname=veyro.cer" "${CERT}"
  File "/oname=runtime.msix" "${DEPENDENCY}"
  File "/oname=veyro.msix" "${MSIX}"
  File "/oname=install-veyro.ps1" "${INSTALLSCRIPT}"

  DetailPrint "Trusting Veyro's publisher certificate..."
  nsExec::ExecToLog 'certutil.exe -f -addstore TrustedPeople "$PLUGINSDIR\veyro.cer"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "The publisher certificate could not be added (code $0)."
    Abort
  ${EndIf}

  DetailPrint "Installing the Windows App Runtime..."
  ; Fails harmlessly when the same or a newer runtime is already installed.
  !insertmacro PS "Add-AppxPackage -Path '$PLUGINSDIR\runtime.msix' -ErrorAction SilentlyContinue; exit 0"

  DetailPrint "Installing Veyro..."
  ; A script file, not -Command: braces and quotes in an inline command did not survive the
  ; NSIS-to-PowerShell command line ("Missing closing '}'").
  nsExec::ExecToStack 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\install-veyro.ps1" -Package "$PLUGINSDIR\veyro.msix"'
  Pop $0
  Pop $1
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "Veyro could not be installed:$\r$\n$1"
    Abort
  ${EndIf}

  CreateDirectory "$INSTDIR"
  WriteUninstaller "$INSTDIR\Uninstall Veyro.exe"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayName" "Veyro"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINSTKEY}" "Publisher" "Veyro"
  WriteRegStr HKLM "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\Uninstall Veyro.exe"
  WriteRegStr HKLM "${UNINSTKEY}" "UninstallString" '"$INSTDIR\Uninstall Veyro.exe"'
  WriteRegStr HKLM "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINSTKEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  ; The optional Veyro Phone Camera add-on lives outside the package; Veyro's own Remove button
  ; unregisters it cleanly, so ask for that first instead of leaving a dead camera behind.
  ReadRegStr $0 HKLM "SOFTWARE\Veyro\VirtualCamera" "UserSid"
  ${If} $0 != ""
    MessageBox MB_OKCANCEL|MB_ICONEXCLAMATION "The Veyro Phone Camera add-on is set up.$\r$\n$\r$\nOpen Veyro's camera window, press the OBS button and choose Remove first. Press OK to uninstall anyway, or Cancel to stop." IDOK +2
    Abort
  ${EndIf}

  DetailPrint "Removing Veyro..."
  !insertmacro PS "Get-AppxPackage -Name Veyro.Windows | Remove-AppxPackage -ErrorAction SilentlyContinue; exit 0"

  DetailPrint "Removing Veyro's publisher certificate..."
  nsExec::ExecToLog 'certutil.exe -delstore TrustedPeople ${THUMBPRINT}'
  Pop $0

  MessageBox MB_YESNO|MB_ICONQUESTION "Also delete Veyro's logs on this PC?$\r$\n$\r$\nFiles you received from your phone are kept." IDNO +2
  RMDir /r "$LOCALAPPDATA\Veyro"

  DeleteRegKey HKLM "${UNINSTKEY}"
  Delete "$INSTDIR\Uninstall Veyro.exe"
  RMDir "$INSTDIR"
SectionEnd
