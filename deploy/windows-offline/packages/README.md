# Offline package directory

This directory is the only source of installation media on the target server.
It is intentionally ignored by Git except for this file.

Run `scripts\Prepare-Bundle.ps1` on a connected build machine to download the
public third-party artifacts, validate the current `application.jar`, the
`protocol-modbus.jar` service descriptor, and the generated network-only
baseline SQL, and update hashes in `manifest\packages.json`.

`protocol-modbus.jar` is an optional protocol package. It is not copied into
the application directory or imported by the baseline SQL. After the normal
installation and verification succeed, run `install_protocol.cmd` to upload
it through the local API and create/update protocol id `modbus-rtu.v1`.

Do not replace a binary without regenerating and reviewing the manifest.
`gplink-baseline.sql` contains retained MQTT authentication configuration and
must be handled as a secret. Never commit or publicly distribute the package
directory or generated ZIP.
