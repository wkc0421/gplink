@{
    # Package paths are resolved relative to the bundle root. They must stay
    # inside the offline bundle so that installation never downloads anything.
    PackageDirectory = '.\packages'
    PackageManifest  = '.\manifest\packages.json'

    # Application and database locations. All four paths must be local,
    # absolute, and pairwise non-overlapping. PostgreSQL paths must be ASCII.
    # Put PostgreSQL data on a dedicated data disk when possible.
    AppRoot      = 'D:\GpLink2'
    PgInstallRoot = 'C:\Program Files\PostgreSQL\16'
    PgData       = 'D:\PostgreSQL\16\data'
    OpenSslInstallRoot = 'C:\Program Files\FireDaemon OpenSSL 3.5'

    ServerPort  = 8848
    PgPort      = 5432
    ExternalHost = '127.0.0.1'
    ExternalPort = 8848

    PgServiceName       = 'GpLinkPostgreSQL16'
    AppServiceName      = 'GpLink2'
    PostgresServiceAccount = 'postgres'
    PostgresSuperUser   = 'postgres'
    PostgresSuperPassword = '<CHANGE_ME_POSTGRES_SUPER_PASSWORD>'
    PostgresServicePassword = '<CHANGE_ME_POSTGRES_SERVICE_PASSWORD>'

    DatabaseName     = 'gplink'
    DatabaseUser     = 'gplink_app'
    DatabasePassword = '<CHANGE_ME_GPLINK_DATABASE_PASSWORD>'
    DatabaseSchema   = 'public'

    # Existing Redis. Redis is not installed by this bundle.
    RedisHost     = '127.0.0.1'
    RedisPort     = 6379
    RedisUser     = ''
    RedisPassword = ''
    RedisDatabase = 0
    AllowPasswordlessRedis = $true

    AdminUsername     = 'admin'
    AdminUserPassword = '<CHANGE_ME_GPLINK_ADMIN_PASSWORD>'

    # Auto: 16-31 GB => Standard16GB; 32 GB or more => High32GB.
    JvmProfile = 'Auto'
    MinimumMemoryGB = 16
    MinimumAppFreeGB = 5
    MinimumPgFreeGB = 20

    # Boolean values must be unquoted $true/$false. Public is deliberately
    # unsupported; FirewallProfiles may contain Domain and/or Private only.
    OpenApiFirewall = $true
    FirewallProfiles = @('Domain', 'Private')

    # Mandatory v1 safety boundaries. RequireWindowsServer accepts the
    # supported Windows Server releases and supported Windows 11 x64 builds.
    RequireAuthenticode = $true
    RequireWindowsServer = $true

    # These are installer arguments, not shell fragments. Keep them as arrays.
    VcRuntimeArguments = @('/install', '/quiet', '/norestart')
    OpenSslArguments = @('/exenoui', '/qn', '/norestart', 'REBOOT=ReallySuppress', 'ADJUSTSYSTEMPATHENV=no')

    # Startup and verification timeouts.
    ServiceStartTimeoutSeconds = 600
    RedisConnectTimeoutSeconds = 10
    ApiVerifyTimeoutSeconds = 600
}
