# Local maintenance only: no push, release publishing, or phone installation.
[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$BuildOnly,
    [switch]$SkipBuild,
    [string]$ForkUrl = 'https://github.com/ymy1990/wcx.git',
    [string]$ForkBranch = 'master',
    [string]$ToolRoot = 'D:\projects\VX\local-analysis\tools',
    [string]$SigningDirectory = 'D:\projects\VX\local-analysis\title-fix\private-signing',
    [string]$OutputDirectory = 'D:\projects\VX\output\maintenance'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = $PSScriptRoot
$requiredBranch = 'codex/merge-secret-friend-wcxlc'
$remoteRef = "refs/remotes/ymy1990/$ForkBranch"

function Invoke-LocalGit {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    & git -C $repo @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "Git command failed: git $($GitArgs -join ' ')" }
}

if ($CheckOnly -and $BuildOnly) { throw 'Choose CheckOnly or BuildOnly, not both.' }
$branch = (Invoke-LocalGit @('branch', '--show-current')).Trim()
if ($branch -ne $requiredBranch) { throw "Run from the maintenance branch: $requiredBranch" }
$mergeHead = (Invoke-LocalGit @('rev-parse', '--git-path', 'MERGE_HEAD')).Trim()
if (-not [IO.Path]::IsPathRooted($mergeHead)) { $mergeHead = Join-Path $repo $mergeHead }
if (Test-Path -LiteralPath $mergeHead) { throw 'An unfinished merge exists. Resolve it or use git merge --abort first.' }

if (-not $BuildOnly) {
    if (@(Invoke-LocalGit @('status', '--porcelain')).Count -gt 0) {
        throw 'Save current local changes as a commit before updating. The script does not discard edits.'
    }
    Write-Host 'Fetching updates from your fork...'
    # A recreated/resynchronized fork may rewind master. Refresh only the tracking ref;
    # the maintenance branch is still preserved and updated by a normal merge below.
    Invoke-LocalGit @('fetch', '--no-tags', $ForkUrl, "+${ForkBranch}:${remoteRef}")
    $behind = (Invoke-LocalGit @('rev-list', '--count', "HEAD..${remoteRef}")).Trim()
    Write-Host "Fork commits not yet incorporated: $behind"
    if ($CheckOnly) { return }
    & git -C $repo merge-base --is-ancestor $remoteRef HEAD
    if ($LASTEXITCODE -ne 0) {
        $backupBranch = 'codex/wcxlc-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
        Invoke-LocalGit @('branch', $backupBranch)
        Write-Host "Local recovery branch: $backupBranch"
        & git -C $repo -c user.name='Codex Local' -c user.email='codex-local@localhost' -c commit.gpgsign=false merge --no-edit $remoteRef
        if ($LASTEXITCODE -ne 0) {
            & git -C $repo diff --name-only --diff-filter=U
            throw 'Merge stopped. Resolve the listed conflicts and commit, then run -BuildOnly. To cancel: git merge --abort.'
        }
    }
}
if ($SkipBuild) { return }

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$catalog = Get-Content -LiteralPath (Join-Path $repo 'app/build.gradle.kts') -Raw
$upstreamName = [regex]::Match($catalog, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$upstreamCodeText = [regex]::Match($catalog, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $upstreamName -or -not $upstreamCodeText) { throw 'Upstream version metadata changed; adjust the local build script.' }
$counterFile = Join-Path $OutputDirectory 'last-version-code.txt'
$previousCode = 3545133
if (Test-Path -LiteralPath $counterFile) { $previousCode = [int](Get-Content -LiteralPath $counterFile -Raw).Trim() }
$versionCode = [Math]::Max($previousCode, [int]$upstreamCodeText) + 1
$versionName = "${upstreamName}-wcxlc-local"
$jdk21 = Join-Path $ToolRoot 'jdk/jdk-21.0.12.1+1'
$jdk17 = (Get-Content -LiteralPath (Join-Path $ToolRoot 'jdk17/home.txt') -Raw).Trim()
$sdk = Join-Path $ToolRoot 'android-sdk'
$keyStore = Join-Path $SigningDirectory 'wcx-local.jks'
$passwordFile = Join-Path $SigningDirectory 'password.txt'
foreach ($path in @((Join-Path $jdk21 'bin/java.exe'), (Join-Path $jdk17 'bin/java.exe'), $sdk, $keyStore, $passwordFile)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required local build dependency missing: $path" }
}

$envNames = @('JAVA_HOME', 'ANDROID_HOME', 'WEKIT_KEYSTORE_FILE', 'WEKIT_KEYSTORE_PASSWORD', 'WEKIT_KEY_ALIAS', 'WEKIT_KEY_PASSWORD')
$savedEnv = @{}
foreach ($name in $envNames) { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$generatedFiles = @('JJTParserState.java', 'Parser.java', 'ParserTokenManager.java', 'ParserTreeConstants.java') |
    ForEach-Object { "libs/common/bsh/src/main/java/bsh/$_" }
$parserWasClean = @(Invoke-LocalGit (@('diff', '--name-only', 'HEAD', '--') + $generatedFiles)).Count -eq 0
$buildLog = Join-Path $OutputDirectory "build-$versionCode.log"
try {
    $env:JAVA_HOME = $jdk21
    $env:ANDROID_HOME = $sdk
    $env:WEKIT_KEYSTORE_FILE = $keyStore
    $env:WEKIT_KEYSTORE_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
    $env:WEKIT_KEY_ALIAS = 'wcx-local'
    $env:WEKIT_KEY_PASSWORD = $env:WEKIT_KEYSTORE_PASSWORD
    $gradleArgs = @('--gradle-user-home', (Join-Path $ToolRoot 'gradle-home'),
        "-Porg.gradle.java.installations.paths=$jdk17,$jdk21", "-PwcxlcVersionCode=$versionCode",
        "-PwcxlcVersionName=$versionName", '--console=plain', ':app:assembleStandardDebug')
    Write-Host "Building $versionName ($versionCode). Build log: $buildLog"
    Push-Location -LiteralPath $repo
    try {
        & (Join-Path $repo 'gradlew.bat') @gradleArgs *> $buildLog
        $buildExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($buildExit -ne 0) {
        Get-Content -LiteralPath $buildLog -Tail 35
        throw "Build failed; see $buildLog"
    }
    $apkDirectory = Join-Path $repo 'app/build/outputs/apk/standard/debug'
    $metadata = Get-Content -LiteralPath (Join-Path $apkDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    $entry = @($metadata.elements | Where-Object { @($_.filters | Where-Object { $_.filterType -eq 'ABI' -and $_.value -eq 'arm64-v8a' }).Count -gt 0 })
    if ($entry.Count -ne 1 -or $entry[0].versionCode -ne $versionCode -or $entry[0].versionName -ne $versionName) {
        throw 'APK metadata does not match this build; refusing to export an older APK.'
    }
    $registryPath = Join-Path $repo 'app/build/generated/ksp/standardDebug/kotlin/com/Johnny/wcx/features/core/FeaturesProvider.kt'
    $registry = Get-Content -LiteralPath $registryPath -Raw
    $controls = [regex]::Matches($registry, '\w+\.apply \{ name = "[^"]+"; categories = listOf\("密友功能"\)')
    if ($controls.Count -lt 38 -or $registry -notmatch 'AutoRestoreOnAppLeave\.apply' -or $registry -notmatch 'AutoRestoreOnLeave\.apply' -or $registry -notmatch 'SecretFriendShownPrompt\.apply' -or $registry -notmatch 'SecretFriendDiagnostics\.apply') {
        throw 'The generated feature registry is missing expected secret-friend controls.'
    }
    $apk = Join-Path $apkDirectory $entry[0].outputFile
    & (Join-Path $sdk 'build-tools/36.0.0/apksigner.bat') verify $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $exported = Join-Path $OutputDirectory "wcx-${versionName}-${versionCode}-arm64-debug.apk"
    Copy-Item -LiteralPath $apk -Destination $exported
    $digest = (Get-FileHash -LiteralPath $exported -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($exported + '.sha256', "$digest  $([IO.Path]::GetFileName($exported))`n")
    [IO.File]::WriteAllText($counterFile, [string]$versionCode)
    Write-Host "APK ready: $exported"
    Write-Host 'Verify title gestures, both restore switches, and unread indicators on the phone before replacing your daily build.'
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process') }
    # BeanShell generation rewrites tracked outputs; preserve any edits present before this build.
    if ($parserWasClean) { Invoke-LocalGit (@('restore', '--') + $generatedFiles) }
}
