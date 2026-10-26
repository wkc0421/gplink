GPLink2 Windows 11 support patch

This patch is for an already extracted GPLink2 offline bundle.

Copy these files into the existing bundle and replace the old files:

  scripts\Common.ps1
  scripts\Preflight.ps1

The patch adds supported x64 Windows 11 builds 22000, 22621, 22631,
26100, and 26200. It does not change application files, PostgreSQL files,
data directories, services, or configuration passwords.

After copying, double-click Check-GpLink.cmd again. The normal memory, disk,
port, administrator, package, and Redis checks still apply.
