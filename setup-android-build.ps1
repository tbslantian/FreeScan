$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$toolRoot = Join-Path $projectRoot '.codex-build-temp'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$downloadRoot = Join-Path $toolRoot 'downloads'
$javaTempRoot = Join-Path $toolRoot 'java-tmp'
$androidUserHome = Join-Path $toolRoot '.android'
$java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
if (!$java) {
    throw 'Java was not found on PATH. Install JDK 17 or newer, then rerun this script.'
}
$javaVersionStart = New-Object System.Diagnostics.ProcessStartInfo
$javaVersionStart.FileName = $java.Source
$javaVersionStart.Arguments = '-version'
$javaVersionStart.UseShellExecute = $false
$javaVersionStart.RedirectStandardOutput = $true
$javaVersionStart.RedirectStandardError = $true
$javaVersionProcess = New-Object System.Diagnostics.Process
$javaVersionProcess.StartInfo = $javaVersionStart
[void]$javaVersionProcess.Start()
$javaVersion = $javaVersionProcess.StandardOutput.ReadToEnd() + $javaVersionProcess.StandardError.ReadToEnd()
$javaVersionProcess.WaitForExit()
if ($javaVersionProcess.ExitCode -ne 0) {
    throw "Could not read Java version: $javaVersion"
}
if ($javaVersion -notmatch 'version "(?<major>\d+)') {
    throw "Could not determine the Java version: $javaVersion"
}
if ([int]$Matches['major'] -lt 17) {
    throw "JDK 17 or newer is required by this project. Found Java $($Matches['major'])."
}

$toolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$toolsSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$toolsZip = Join-Path $downloadRoot 'commandlinetools-win-15859902_latest.zip'
$toolsExtractRoot = Join-Path $toolRoot 'commandlinetools-extract'
$toolsTarget = Join-Path $sdkRoot 'cmdline-tools\latest'
$sdkManager = Join-Path $toolsTarget 'bin\sdkmanager.bat'

New-Item -ItemType Directory -Force -Path $downloadRoot, $sdkRoot, $javaTempRoot, $androidUserHome | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$javaTempRoot"
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_USER_HOME = $androidUserHome
$env:ANDROID_SDK_HOME = $toolRoot

if (!(Test-Path -LiteralPath $sdkManager)) {
    if (Test-Path -LiteralPath $toolsTarget) {
        throw "Found an incomplete command-line tools folder at $toolsTarget. Remove that folder, then rerun this script."
    }

    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source --fail --location --retry 3 --output $toolsZip $toolsUrl
        if ($LASTEXITCODE -ne 0) {
            Remove-Item -LiteralPath $toolsZip -Force -ErrorAction SilentlyContinue
        }
    }

    if (!(Test-Path -LiteralPath $toolsZip)) {
        $javaDownloader = Join-Path $toolRoot 'DownloadFile.java'
        @'
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DownloadFile {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(args[0]).toURL().openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
            throw new IllegalStateException("Download returned HTTP " + connection.getResponseCode());
        }
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, Path.of(args[1]), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }
}
'@ | Set-Content -LiteralPath $javaDownloader -Encoding ASCII
        & java.exe $javaDownloader $toolsUrl $toolsZip
        if ($LASTEXITCODE -ne 0) {
            throw "Android command-line tools download failed with exit code $LASTEXITCODE."
        }
    }

    $actualHash = (Get-FileHash -LiteralPath $toolsZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $toolsSha256) {
        throw "Android command-line tools checksum mismatch. Expected $toolsSha256, got $actualHash."
    }

    Expand-Archive -LiteralPath $toolsZip -DestinationPath $toolsExtractRoot -Force
    $extractedTools = Join-Path $toolsExtractRoot 'cmdline-tools'
    if (!(Test-Path -LiteralPath (Join-Path $extractedTools 'bin\sdkmanager.bat'))) {
        throw 'The downloaded command-line tools archive did not contain sdkmanager.bat.'
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $toolsTarget) | Out-Null
    Move-Item -LiteralPath $extractedTools -Destination $toolsTarget
    Remove-Item -LiteralPath $toolsZip -Force

    $toolRootFull = [IO.Path]::GetFullPath($toolRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $extractRootFull = [IO.Path]::GetFullPath($toolsExtractRoot)
    if (!$extractRootFull.StartsWith($toolRootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove extraction path outside the project tool directory: $extractRootFull"
    }
    Remove-Item -LiteralPath $toolsExtractRoot -Recurse -Force
}

$sdkRootArg = "--sdk_root=$sdkRoot"
Write-Host 'Accept the Android SDK licenses when sdkmanager prompts.'
& $sdkManager $sdkRootArg --licenses
if ($LASTEXITCODE -ne 0) {
    throw "Android SDK license setup failed with exit code $LASTEXITCODE."
}

& $sdkManager $sdkRootArg --install 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) {
    throw "Android SDK package installation failed with exit code $LASTEXITCODE."
}

$localProperties = Join-Path $projectRoot 'local.properties'
$sdkPropertyPath = $sdkRoot.Replace('\', '/')
$encoding = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($localProperties, "sdk.dir=$sdkPropertyPath$([Environment]::NewLine)", $encoding)

$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
& $gradleWrapper --version
if ($LASTEXITCODE -ne 0) {
    throw "Gradle Wrapper setup failed with exit code $LASTEXITCODE."
}

Write-Host "Android SDK installed at: $sdkRoot"
Write-Host 'Gradle 8.13 is available through the project Gradle Wrapper.'
Write-Host 'Setup is complete. Run your Gradle compile command separately when ready.'
