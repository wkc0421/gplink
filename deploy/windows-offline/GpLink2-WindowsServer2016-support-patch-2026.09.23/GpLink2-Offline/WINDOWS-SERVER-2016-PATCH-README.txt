GPLink2 Windows Server 2016 support patch

This patch is for an already extracted GPLink2 offline bundle. It supersedes the 2026.09.21 Windows 11 support patch and retains its supported Windows 11 builds.

Copy these files into the existing bundle and replace the old files:

  scripts\Common.ps1
  scripts\Preflight.ps1

The patch adds x64 Windows Server 2016 build 14393 to the supported OS checks. After copying the files, double-click Check-GpLink.cmd again. The normal memory, disk, port, administrator, package, and Redis checks still apply.

This patch does not change application files, PostgreSQL files, data directories, services, or configuration passwords.

Compatibility note: TimescaleDB's official Windows platform list names Windows Server 2019 and 2022, but not Server 2016. Complete preflight, installation, verification, and reboot validation on Server 2016 before production use. Microsoft support for Windows Server 2016 ends in January 2027.
