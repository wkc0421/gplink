GPLink2 Java 17 JDK priority patch

This is a small script-only patch for an already extracted GPLink2 Windows
offline bundle. It does not contain the 700 MB offline installers or change
application/database data.

Apply it by extracting this ZIP into the parent directory that contains your
existing GpLink2-Offline folder, for example D:\tool\GpLink2. Allow the files
under GpLink2-Offline to merge and replace the existing files. The patch
replaces these installer scripts and the bundle README:

  GpLink2-Offline\scripts\Common.ps1
  GpLink2-Offline\scripts\Preflight.ps1
  GpLink2-Offline\scripts\Install-GpLinkService.ps1
  GpLink2-Offline\scripts\Install.ps1
  GpLink2-Offline\scripts\Verify.ps1
  GpLink2-Offline\scripts\Reinstall.ps1
  GpLink2-Offline\README.md

Then run Check-GpLink.cmd and Install-GpLink.cmd from the existing
GpLink2-Offline folder. The installer first tests
D:\Program Files\Java\jdk-17\bin\java.exe and javac.exe. If both identify as
x64 Java 17, NSSM uses that JDK. Otherwise it installs the verified bundled
Temurin JRE. The selected Java mode, path, and version are written to
%ProgramData%\GpLink2\install-state.json and checked during verification.

Applying this patch only replaces installer files; it does not itself stop
services, install Java, change PostgreSQL, or alter application data.
