param(
    [Parameter(Mandatory=$true)][string]$PluginJar,
    [Parameter(Mandatory=$true)][string]$ProbeJar,
    [Parameter(Mandatory=$true)][string]$Fixture,
    [Parameter(Mandatory=$true)][string]$InputManifest,
    [Parameter(Mandatory=$true)][string]$LuckPermsJar,
    [string[]]$Lanes=@('leaf-12111','leaf-262','folia-12111','folia-262'),
    [string]$RunLabel=([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')),
    [ValidateSet('core','end','legacy','domain','missions','upgrade','commands')][string]$Profile='core',
    [string]$UpstreamJar,
    [string]$SeedJar,
    [int]$TimeoutSeconds=550
)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$localRoot=Join-Path $workspace 'local-servers'
$gatePath=Join-Path $workspace '.ai-control\evidence\2026-09-30-plugin-multiversion-dispatch\build.lock'
$plugin=(Resolve-Path -LiteralPath $PluginJar).Path
$probe=(Resolve-Path -LiteralPath $ProbeJar).Path
$luckPerms=(Resolve-Path -LiteralPath $LuckPermsJar).Path
$fixturePath=(Resolve-Path -LiteralPath $Fixture).Path
$manifest=Get-Content -LiteralPath $InputManifest -Raw | ConvertFrom-Json
$utf8=[Text.UTF8Encoding]::new($false)
$node=(Get-Command node.exe).Source
$gate=$null
$upstream=$null;$seed=$null
$upstreamExpectedSHA='552A9AD27B2CCF0B6A8E124805220333C125E361A992A8E82DEB62088BA831C1'
if($Profile-eq'upgrade'){
    if($Lanes.Count-ne1-or$Lanes[0]-ne'leaf-262'){throw 'Populated upstream upgrade profile accepts only the explicit leaf-262 lane.'}
    if([string]::IsNullOrWhiteSpace($UpstreamJar)-or[string]::IsNullOrWhiteSpace($SeedJar)){throw 'Upgrade profile requires explicit private UpstreamJar and SeedJar inputs.'}
    $upstream=(Resolve-Path -LiteralPath $UpstreamJar).Path
    $seed=(Resolve-Path -LiteralPath $SeedJar).Path
    foreach($privateInput in @($upstream,$seed)){
        $absolute=[IO.Path]::GetFullPath($privateInput)
        if(-not$absolute.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or
            (Get-Item -LiteralPath $absolute).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Upgrade inputs must be regular private workspace files, never production paths.'}
        $ancestor=Split-Path -Parent $absolute
        while($ancestor-ne$workspace){
            if([string]::IsNullOrWhiteSpace($ancestor)-or-not$ancestor.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or
                (Get-Item -LiteralPath $ancestor).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Upgrade input traverses a non-private or reparse ancestor.'}
            $ancestor=Split-Path -Parent $ancestor
        }
    }
    if((Get-FileHash -LiteralPath $upstream).Hash-ne$upstreamExpectedSHA){throw 'Unmodified installed SuperiorSkyblock2 2026.3 source digest differs.'}
    if((Get-Item -LiteralPath $fixturePath).Attributes-band[IO.FileAttributes]::ReparsePoint-or
        @(Get-ChildItem -LiteralPath $fixturePath -Recurse -Force | Where-Object {$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'Upgrade fixture must contain no reparse points.'}
}
function Free-RAM { return (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB }
function Free-Port {
    foreach($candidate in 29010..29029) {
        $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$candidate)
        try {$listener.Start();return $candidate} catch [Net.Sockets.SocketException] {} finally {$listener.Stop()}
    }
    throw 'Private fixture ports occupied; no production fallback.'
}
function New-Process([string]$Exe,[string]$Directory,[string[]]$Arguments,[hashtable]$Environment=@{}) {
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Exe;$info.WorkingDirectory=$Directory
    foreach($argument in $Arguments){$info.ArgumentList.Add($argument)}
    foreach($key in $Environment.Keys){$info.Environment[$key]=[string]$Environment[$key]}
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.WindowStyle='Hidden'
    $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;return $process
}
function Stop-Own([Diagnostics.Process]$Process) {
    if($null-ne$Process-and-not$Process.HasExited){$Process.Kill($true);[void]$Process.WaitForExit(10000)}
}
function Assert-UpgradeRoot([string]$Root) {
    $absolute=[IO.Path]::GetFullPath($Root)
    if($Profile-ne'upgrade'-or(Split-Path -Parent $absolute)-ne$localRoot-or
        (Split-Path -Leaf $absolute)-notlike'superior-runtime-*'-or
        -not(Test-Path -LiteralPath (Join-Path $absolute '.superior-owned') -PathType Leaf)-or
        -not(Test-Path -LiteralPath (Join-Path $absolute '.superior-upgrade-owned') -PathType Leaf)){
        throw 'Upgrade action refuses an unowned fixture root.'
    }
    foreach($guardPath in @($absolute,(Join-Path $absolute 'plugins'),(Join-Path $absolute '.superior-owned'),(Join-Path $absolute '.superior-upgrade-owned'))){
        if((Get-Item -LiteralPath $guardPath).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Upgrade guard contains a reparse point.'}
    }
}
function Get-UpgradeState([string]$Root) {
    Assert-UpgradeRoot $Root
    $files=[Collections.Generic.List[string]]::new()
    $directories=@((Join-Path $Root 'plugins/SuperiorSkyblock2'),(Join-Path $Root 'plugins/LuckPerms'),(Join-Path $Root 'config'))
    foreach($directory in Get-ChildItem -LiteralPath $Root -Directory -Force){
        if((Test-Path -LiteralPath (Join-Path $directory.FullName 'level.dat') -PathType Leaf)-or
            (Test-Path -LiteralPath (Join-Path $directory.FullName 'uid.dat') -PathType Leaf)){$directories+=$directory.FullName}
    }
    foreach($directory in $directories){
        if(-not(Test-Path -LiteralPath $directory -PathType Container)){continue}
        if((Get-Item -LiteralPath $directory).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Retained upgrade state contains a reparse directory.'}
        foreach($entry in Get-ChildItem -LiteralPath $directory -Recurse -Force){
            if($entry.Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Retained upgrade state contains a reparse point.'}
            if(-not$entry.PSIsContainer){$files.Add($entry.FullName)}
        }
    }
    foreach($name in @('server.properties','bukkit.yml','spigot.yml','purpur.yml','ops.json','whitelist.json','banned-players.json','banned-ips.json',
        'upstream-island-checkpoint.properties','upstream-island-checkpoint.properties.sha256')){
        $file=Join-Path $Root $name
        if(Test-Path -LiteralPath $file -PathType Leaf){$files.Add($file)}
    }
    $state=[ordered]@{}
    foreach($file in @($files | Sort-Object -Unique)){
        $absolute=[IO.Path]::GetFullPath($file)
        if(-not$absolute.StartsWith($Root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or
            (Get-Item -LiteralPath $absolute).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Retained-state hash target escaped the private fixture.'}
        $relative=$absolute.Substring($Root.Length+1).Replace('\','/')
        $state[$relative]=(Get-FileHash -LiteralPath $absolute).Hash
    }
    return $state
}
function Assert-UpgradeStateEqual($Before,$After) {
    if($Before.Count-ne$After.Count){throw 'Private JAR cutover added or removed retained config/database/world state.'}
    foreach($key in $Before.Keys){if(-not$After.Contains($key)-or$Before[$key]-ne$After[$key]){throw "Private JAR cutover altered retained state: $key"}}
}
function Run-Phase([string]$Root,[string]$Phase,[string]$Java,[int]$Port,[string]$Version,[string]$Mineflayer) {
    if((Free-RAM)-lt8GB){throw 'Less than 8 GiB free physical RAM; native phase not started.'}
    $server=New-Process $Java $Root @('-Xms256M','-Xmx1536M','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC',('-Djdk.net.unixdomain.tmpdir='+$workspace+'/.ai-control/tmp'),'-jar','server.jar','--nogui')
    $client=$null;$stdout=[Text.StringBuilder]::new();$read=$null;$stderr=$null;$out=$null;$err=$null
    $ready=$false;$passed=$false;$clientPassed=$false;$forced=$false;$intentionalCrash=$false;$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $clientPID=$null
    $expected=if($Profile-eq'upgrade'){
        switch($Phase){
            'upstream-seed' {'SUPERIOR_UPSTREAM_SEED_PASS'}
            'fork-upgrade' {'SUPERIOR_UPGRADE_MIGRATION_PASS'}
            'fork-upgrade-restart' {'SUPERIOR_UPGRADE_RESTART_PASS'}
            default {throw 'Unknown populated upstream upgrade phase.'}
        }
    }elseif($Profile-eq'legacy'){
        if($Phase-eq'initial'){'SUPERIOR_LEGACY_PROBE_PASS'}else{'SUPERIOR_LEGACY_RESTART_PASS'}
    }elseif($Profile-eq'domain'){
        switch($Phase){
            'initial' {'SUPERIOR_DOMAIN_CHECKPOINT_ACK'}
            'cold-reset-intent' {'SUPERIOR_DOMAIN_COLD_RESET_INTENT_ACK'}
            'cold-transfer-intent' {'SUPERIOR_DOMAIN_COLD_TRANSFER_INTENT_ACK'}
            'cold-delete-intent' {'SUPERIOR_DOMAIN_COLD_DELETE_INTENT_ACK'}
            'delete-restart' {'SUPERIOR_DOMAIN_COLD_DELETE_PASS'}
            'storage-reset-failure' {'SUPERIOR_DOMAIN_STORAGE_RESET_FAILURE_PASS'}
            'storage-delete-failure' {'SUPERIOR_DOMAIN_STORAGE_DELETE_FAILURE_PASS'}
            'storage-reset-hold' {'SUPERIOR_DOMAIN_STORAGE_RESET_WORLD_ACK_HELD'}
            'storage-reset-restart' {'SUPERIOR_DOMAIN_STORAGE_RESTART_PASS'}
            'storage-delete-restart' {'SUPERIOR_DOMAIN_STORAGE_RESTART_PASS'}
            'storage-reset-crash-restart' {'SUPERIOR_DOMAIN_STORAGE_RESTART_PASS'}
            default {'SUPERIOR_DOMAIN_RESTART_PASS'}
        }
    }elseif($Profile-eq'commands'){
        if($Phase-eq'restart'){'SUPERIOR_COMMAND_RESTART_PASS'}else{'SUPERIOR_COMMAND_PROBE_PASS'}
    }elseif($Profile-eq'missions'){
        if($Phase-eq'restart'){'SUPERIOR_MISSION_RESTART_PASS'}else{'SUPERIOR_MISSION_PROBE_PASS'}
    }else{if($Phase-eq'restart'){'SUPERIOR_RESTART_PASS'}else{'SUPERIOR_PROBE_PASS'}}
    try {
        [void]$server.Start();$server.PriorityClass='BelowNormal'
        $stderr=$server.StandardError.ReadToEndAsync();$read=$server.StandardOutput.ReadLineAsync()
        while(-not$server.HasExited-and[DateTime]::UtcNow-lt$deadline) {
            if($read.Wait(250)) {
                $line=$read.Result
                if($null-ne$line) {
                    [void]$stdout.AppendLine($line)
                    if($line-match'Done \([0-9.]+s\)!'-and-not$ready) {
                        $ready=$true
                        $driver=if($Profile-eq'upgrade'){'upstream-upgrade-probe.mjs'}elseif($Profile-eq'legacy'){'legacy-probe.mjs'}elseif($Profile-eq'domain'){'domain-probe.mjs'}elseif($Profile-eq'commands'){'command-probe.mjs'}elseif($Profile-eq'missions'){'mission-probe.mjs'}else{'player-probe.mjs'}
                        $client=New-Process $node $PSScriptRoot @('--max-old-space-size=256',(Join-Path $PSScriptRoot $driver)) @{
                            SUPERIOR_TEST_PORT=[string]$Port;SUPERIOR_TEST_VERSION=$Version;SUPERIOR_MINEFLAYER=$Mineflayer;SUPERIOR_PHASE=$Phase;SUPERIOR_PROFILE=$Profile;SUPERIOR_TEST_ROOT=$Root
                        }
                        [void]$client.Start();$clientPID=$client.Id;$client.PriorityClass='BelowNormal';$out=$client.StandardOutput.ReadToEndAsync();$err=$client.StandardError.ReadToEndAsync()
                    }
                    if($line-match'SUPERIOR_((ADVANCED_|LEGACY_|MISSION_|COMMAND_)?PROBE_FAIL|DOMAIN_FAIL|UPSTREAM_SEED_FAIL|UPGRADE_FAIL)'){break}
                    if($line.Contains($expected)){$passed=$true}
                }
                $read=$server.StandardOutput.ReadLineAsync()
            }
            if($null-ne$client-and$client.HasExited) {
                $clientPassed=$client.ExitCode-eq0-and$out.Result-match'SUPERIOR_(LEGACY_|MISSION_|UPGRADE_|COMMAND_)?CLIENT_PASS'
                if(-not$clientPassed){break}
            }
            if($passed-and$clientPassed){$intentionalCrash=$Profile-eq'domain'-and$Phase-eq'storage-reset-hold';break}
        }
    } finally {
        if($null-ne$client) {
            Stop-Own $client
            if($null-ne$out){[IO.File]::WriteAllText((Join-Path $Root ($Phase+'-client.stdout.log')),$out.Result,$utf8)}
            if($null-ne$err){[IO.File]::WriteAllText((Join-Path $Root ($Phase+'-client.stderr.log')),$err.Result,$utf8)}
            $client.Dispose()
        }
        if($intentionalCrash-and-not$server.HasExited){Stop-Own $server}
        if(-not$server.HasExited) {
            try {$server.StandardInput.WriteLine('stop');$server.StandardInput.Flush()} catch {}
            $stopDeadline=[DateTime]::UtcNow.AddSeconds(45)
            while(-not$server.HasExited-and[DateTime]::UtcNow-lt$stopDeadline) {
                if($read.Wait(250)) {if($null-ne$read.Result){[void]$stdout.AppendLine($read.Result)};$read=$server.StandardOutput.ReadLineAsync()}
            }
            if(-not$server.HasExited){$forced=$true;Stop-Own $server}
        }
        if($null-ne$read-and$read.Wait(1000)-and$null-ne$read.Result){[void]$stdout.AppendLine($read.Result)}
        [void]$stdout.Append($server.StandardOutput.ReadToEnd())
        [IO.File]::WriteAllText((Join-Path $Root ($Phase+'-runtime.stdout.log')),$stdout.ToString(),$utf8)
        if($null-ne$stderr){[IO.File]::WriteAllText((Join-Path $Root ($Phase+'-runtime.stderr.log')),$stderr.Result,$utf8)}
    }
    # Retain the complete log; exclude only the unmodified Windows wrapper's
    # precise pre-plugin OSHI system-report diagnostic from plugin failures.
    $normalizedLog=[regex]::Replace($stdout.ToString(),'\x1b\[[0-9;]*m','')
    $logBlocks=[regex]::Split($normalizedLog,'(?m)(?=^\[\d{2}:\d{2}:\d{2} (?:INFO|WARN|ERROR)\])')
    $knownRuntimeWarnings=0
    $qualifiedBlocks=[Collections.Generic.List[string]]::new()
    foreach($block in $logBlocks){
        $known=$block-match'Failed to get system info for Process (Loads|(Virtual|Resident) Size)'-and
            $block-match'java.lang.NullPointerException: Cannot invoke "oshi.software.os.OSProcess.get(UserTime|VirtualSize|ResidentSetSize)'-and
            $block-match'net.minecraft.SystemReport'-and$block-notmatch'SuperiorSkyblock|PVCSkyProbe'
        if($known){$knownRuntimeWarnings++}else{$qualifiedBlocks.Add($block)}
    }
    $qualifiedOutput=$qualifiedBlocks-join''
    $ownershipError=$qualifiedOutput-match'TickThread.ensureTickThread|Accessing entity state off owning region|Not on any region|java.lang.(UnsupportedOperationException|NullPointerException|NoSuchMethodError|AbstractMethodError|NoClassDefFoundError|ExceptionInInitializerError)|UnsupportedClassVersionError|Error occurred while enabling Superior|Error initializing plugin|Exception in thread|SUPERIOR_((ADVANCED_|LEGACY_|MISSION_|COMMAND_)?PROBE_FAIL|DOMAIN_FAIL|UPSTREAM_SEED_FAIL|UPGRADE_FAIL)'
    $result=@{phase=$Phase;profile=$Profile;ready=$ready;probePassed=$passed;clientPassed=$clientPassed;ownershipError=$ownershipError;knownRuntimeWarnings=$knownRuntimeWarnings;forcedStop=$forced;intentionalOwnedCrash=$intentionalCrash;exitCode=$server.ExitCode;productionTouched=$false;serverPID=$server.Id;clientPID=$clientPID}
    [IO.File]::WriteAllText((Join-Path $Root ($Phase+'-result.json')),($result|ConvertTo-Json),$utf8)
    $server.Dispose();Write-Output ($result|ConvertTo-Json -Compress)
    if(-not$ready-or-not$passed-or-not$clientPassed-or$ownershipError-or$forced-or(-not$intentionalCrash-and$result.exitCode-ne0)){throw "Native $Phase failed: evidence retained at $Root"}
}
try {
    # An operator may stop an owned matrix at a profile boundary without
    # interrupting a live JVM or rewriting an immutable qualification input.
    if(Test-Path -LiteralPath (Join-Path $workspace '.ai-control/workspace-local/superior-native.stop')){
        throw 'Native matrix stop requested; no new profile or JVM started.'
    }
    $deadline=[DateTime]::UtcNow.AddMinutes(20)
    while($null-eq$gate) {
        if([DateTime]::UtcNow-gt$deadline){throw 'Shared gate timeout; no native process started.'}
        if((Free-RAM)-lt8GB){Start-Sleep -Seconds 5;continue}
        try {$gate=[IO.File]::Open($gatePath,'OpenOrCreate','ReadWrite','None')} catch [IO.IOException] {Start-Sleep -Seconds 2}
    }
    Write-Output 'SUPERIOR_RUNTIME_GATE_ACQUIRED'
    foreach($id in $Lanes) {
        $lane=@($manifest.lanes|Where-Object id -eq $id)
        if($lane.Count-ne1){throw "Unknown or duplicated lane $id"};$lane=$lane[0]
        if($Profile-eq'end'-and$lane.platform-ne'Folia'){throw 'Regional End profile only accepts actual Folia.'}
        if($Profile-eq'upgrade'-and($lane.platform-ne'Leaf'-or$lane.minecraft-ne'26.2')){throw 'Populated upstream upgrade profile requires actual Leaf 26.2.'}
        $root=[IO.Path]::GetFullPath((Join-Path $localRoot ('superior-runtime-'+$RunLabel+'-'+$id)))
        if((Split-Path -Parent $root)-ne$localRoot-or(Split-Path -Leaf $root)-notlike'superior-runtime-*'){throw 'Unsafe fixture root.'}
        if(Test-Path -LiteralPath $root){throw 'Refusing to overwrite prior evidence.'}
        $serverSource=Join-Path $workspace $lane.serverJar
        if((Get-FileHash -LiteralPath $serverSource).Hash-ne$lane.sha256){throw 'Unmodified runtime source hash mismatch.'}
        [void][IO.Directory]::CreateDirectory((Join-Path $root 'plugins'))
        [IO.File]::WriteAllText((Join-Path $root '.superior-owned'),$RunLabel,$utf8)
        if($Profile-eq'upgrade'){[IO.File]::WriteAllText((Join-Path $root '.superior-upgrade-owned'),'populated-upstream-upgrade',$utf8)}
        Copy-Item -LiteralPath $serverSource -Destination (Join-Path $root 'server.jar')
        $serverParent=Split-Path -Parent $serverSource
        foreach($cacheName in @('libraries','cache')) {
            $cache=Join-Path $serverParent $cacheName
            if(Test-Path -LiteralPath $cache -PathType Container){
                if((Get-Item -LiteralPath $cache).Attributes-band[IO.FileAttributes]::ReparsePoint){throw 'Cache root is a reparse point.'}
                if(@(Get-ChildItem -LiteralPath $cache -Recurse -Force | Where-Object {$_.Attributes-band[IO.FileAttributes]::ReparsePoint}).Count){throw 'Cache contains a reparse point.'}
                Copy-Item -LiteralPath $cache -Destination (Join-Path $root $cacheName) -Recurse
            }
        }
        Copy-Item -LiteralPath $fixturePath -Destination (Join-Path $root 'plugins/SuperiorSkyblock2') -Recurse
        if($Profile-eq'upgrade'){
            Copy-Item -LiteralPath $upstream -Destination (Join-Path $root 'plugins/SuperiorSkyblock2.jar')
            Copy-Item -LiteralPath $seed -Destination (Join-Path $root 'plugins/SuperiorSkyblockUpstreamSeed.jar')
        }else{
            Copy-Item -LiteralPath $plugin -Destination (Join-Path $root 'plugins/SuperiorSkyblock2.jar')
            Copy-Item -LiteralPath $probe -Destination (Join-Path $root 'plugins/SuperiorSkyblockPVCProbe.jar')
        }
        Copy-Item -LiteralPath $luckPerms -Destination (Join-Path $root 'plugins/LuckPerms.jar')
        $configPath=Join-Path $root 'plugins/SuperiorSkyblock2/config.yml'
        $config=[IO.File]::ReadAllText($configPath)
        if($lane.platform-eq'Folia'){$config=$config.Replace('location: world, 0.5, 100, 0.5, 0, 0','location: SuperiorWorld, 0.5, 100, 0.5, 0, 0')}
        if($Profile-eq'end'){
            $config=$config.Replace("    the_end:`n      enabled: false`n      unlock: false", "    the_end:`n      enabled: true`n      unlock: true")
            $config=$config.Replace("      dragon-fight:`n        enabled: false", "      dragon-fight:`n        enabled: true")
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../src/main/resources/schematics/normal_the_end.schematic') -Destination (Join-Path $root 'plugins/SuperiorSkyblock2/schematics/classic_the_end.schematic')
        }
        if($Profile-eq'domain'){
            $config=$config.Replace('max-island-size: 1000','max-island-size: 64').Replace('  island-size: 100','  island-size: 20')
            $privateDatabase=[IO.Path]::GetFullPath((Join-Path $root 'plugins/SuperiorSkyblock2/datastore/database.db'))
            if(-not$privateDatabase.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe tiny-grid database path.'}
            if(Test-Path -LiteralPath $privateDatabase){Remove-Item -LiteralPath $privateDatabase -Force}
        }
        if($Profile-eq'missions'){
            $config=$config.Replace('max-island-size: 1000','max-island-size: 96').Replace('  island-size: 100','  island-size: 32')
            $privateDatabase=[IO.Path]::GetFullPath((Join-Path $root 'plugins/SuperiorSkyblock2/datastore/database.db'))
            if(-not$privateDatabase.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fresh mission database path.'}
            if(Test-Path -LiteralPath $privateDatabase){Remove-Item -LiteralPath $privateDatabase -Force}
            $missionTarget=Join-Path $root 'plugins/SuperiorSkyblock2/modules/missions'
            [void][IO.Directory]::CreateDirectory($missionTarget)
            Copy-Item -Path (Join-Path $PSScriptRoot '../fixtures/native-missions/*') -Destination $missionTarget -Recurse -Force
            [IO.File]::WriteAllText((Join-Path $root '.superior-missions-owned'),'mission fixture',$utf8)
        }
        if($Profile-eq'commands'){
            $config=$config.Replace('max-island-size: 1000','max-island-size: 96').Replace('  island-size: 100','  island-size: 32').Replace('  warps-limit: 0','  warps-limit: 4').Replace('  team-limit: 1','  team-limit: 4')
            $config=[regex]::Replace($config,'(?ms)^disabled-commands:.*?(?=^[a-zA-Z][a-zA-Z0-9-]*:)', 'disabled-commands: []'+"`n")
            $config=$config.Replace('dangerous-commands: []','dangerous-commands: [demote, kick, setsize, addsize, removeratings, adddisbands]')
            $privateDatabase=[IO.Path]::GetFullPath((Join-Path $root 'plugins/SuperiorSkyblock2/datastore/database.db'))
            if(-not$privateDatabase.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fresh command database path.'}
            if(Test-Path -LiteralPath $privateDatabase){Remove-Item -LiteralPath $privateDatabase -Force}
            foreach($target in @('modules/upgrades','modules/missions/categories/pvc_native','menus')){[void][IO.Directory]::CreateDirectory((Join-Path $root ('plugins/SuperiorSkyblock2/'+$target)))}
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../runtime-probe/native-commands-upgrades.yml') -Destination (Join-Path $root 'plugins/SuperiorSkyblock2/modules/upgrades/config.yml') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../runtime-probe/native-commands-missions.yml') -Destination (Join-Path $root 'plugins/SuperiorSkyblock2/modules/missions/config.yml') -Force
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../runtime-probe/native-commands-items.yml') -Destination (Join-Path $root 'plugins/SuperiorSkyblock2/modules/missions/categories/pvc_native/pvc_command_items.yml') -Force
            # Retain the upstream layout/handler; modern fixtures use the native material name.
            $ratingMenu=[IO.File]::ReadAllText((Join-Path $PSScriptRoot '../../src/main/resources/menus/island-rate.yml')).Replace('type: SKULL_ITEM','type: PLAYER_HEAD')
            [IO.File]::WriteAllText((Join-Path $root 'plugins/SuperiorSkyblock2/menus/island-rate.yml'),$ratingMenu,$utf8)
            [IO.File]::WriteAllText((Join-Path $root '.superior-commands-owned'),'command fixture',$utf8)
        }
        if($Profile-notin@('legacy','upgrade')){$config+="`npvc:`n  size-mode: PERMISSION_AUTOMATIC`n  entitlements:`n    refresh-seconds: 1`n"}
        if($Profile-eq'upgrade'){
            if([regex]::IsMatch($config,'(?m)^pvc\s*:')){throw 'Populated upstream upgrade fixture must not preseed PVC settings.'}
            # Seed the exact copied LEGACY bytes, including encoding/newlines.
            [IO.File]::Copy($configPath,(Join-Path $root 'upstream-before-config.yml'))
            [IO.File]::Copy($configPath,(Join-Path $root 'staged-before-config.yml'))
        }else{
            [IO.File]::WriteAllText($configPath,$config,$utf8)
            [IO.File]::WriteAllText((Join-Path $root 'staged-before-config.yml'),$config,$utf8)
        }
        $port=Free-Port
        $level=if($lane.platform-eq'Folia'){'SuperiorWorld'}else{'world'}
        # Folia counts pending simultaneous login reservations in its capacity
        # check. Leave two spare admission slots while still using only the two
        # fixed synthetic accounts; this does not grant a permission bypass.
        $maxPlayers=if($Profile-in@('end','domain','missions','commands')){4}else{2}
        [IO.File]::WriteAllText((Join-Path $root 'eula.txt'),"eula=true`n",$utf8)
        [IO.File]::WriteAllText((Join-Path $root 'server.properties'),@"
online-mode=false
server-ip=127.0.0.1
server-port=$port
enable-query=false
enable-rcon=false
enforce-secure-profile=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=$maxPlayers
level-name=$level
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:grass_block","height":1}],"features":false,"lakes":false,"structure_overrides":[]}
generate-structures=false
allow-nether=false
gamemode=creative
sync-chunk-writes=false
motd=Superior isolated synthetic fixture
"@,$utf8)
        [void][IO.Directory]::CreateDirectory((Join-Path $root 'config'))
        [IO.File]::WriteAllText((Join-Path $root 'config/paper-global.yml'),"_version: 31`nchunk-system:`n  io-threads: 1`n  worker-threads: 1`nthreaded-regions:`n  threads: 2`nspark:`n  enabled: false`n",$utf8)
        $endEnabled=if($Profile-eq'end'){'true'}else{'false'}
        $endGenerator=if($Profile-eq'end'){"  SuperiorWorld_the_end:`n    generator: SuperiorSkyblock2`n"}else{''}
        [IO.File]::WriteAllText((Join-Path $root 'bukkit.yml'),"settings:`n  allow-end: $endEnabled`n  connection-throttle: 0`nworlds:`n  SuperiorWorld:`n    generator: SuperiorSkyblock2`n"+$endGenerator,$utf8)
        [void][IO.Directory]::CreateDirectory((Join-Path $root 'plugins/bStats'))
        [IO.File]::WriteAllText((Join-Path $root 'plugins/bStats/config.yml'),"enabled: false`n",$utf8)
        $java=if($lane.java-eq'21'){'C:/Program Files/Amazon Corretto/jdk21.0.3_9/bin/java.exe'}else{'C:/Java/temurin-jre-25/bin/java.exe'}
        $clientRoot=Join-Path $workspace '.ai-control/workspace-local/pvc-wither-client'
        if($lane.minecraft-eq'26.2'){$clientRoot=Join-Path $clientRoot '26.2-source'}
        $mineflayer=Join-Path $clientRoot 'node_modules/mineflayer/index.js'
        if(-not(Test-Path -LiteralPath $mineflayer)){throw 'Native client unavailable.'}
        [IO.File]::WriteAllText((Join-Path $root 'runtime-input.json'),(@{lane=$lane;pluginSHA256=(Get-FileHash $plugin).Hash;probeSHA256=(Get-FileHash $probe).Hash;port=$port;productionTouched=$false}|ConvertTo-Json -Depth 6),$utf8)
        Write-Output "SUPERIOR_RUNTIME_START $id $root"
        if($Profile-eq'upgrade'){
            Assert-UpgradeRoot $root
            $sourceRecord=[ordered]@{
                profile='upgrade';lane=$lane;port=$port;productionTouched=$false
                original=[ordered]@{pluginSHA256=(Get-FileHash -LiteralPath $upstream).Hash;seedSHA256=(Get-FileHash -LiteralPath $seed).Hash;
                    luckPermsSHA256=(Get-FileHash -LiteralPath $luckPerms).Hash;configSHA256=(Get-FileHash -LiteralPath $configPath).Hash}
                candidate=[ordered]@{pluginSHA256=(Get-FileHash -LiteralPath $plugin).Hash;probeSHA256=(Get-FileHash -LiteralPath $probe).Hash}
                seed=$null;cutover=$null;final=$null
            }
            $sourcePath=Join-Path $root 'upgrade-source.json'
            [IO.File]::WriteAllText($sourcePath,($sourceRecord|ConvertTo-Json -Depth 10),$utf8)
            Run-Phase $root 'upstream-seed' $java $port $lane.minecraft $mineflayer
            Assert-UpgradeRoot $root
            $checkpoint=Join-Path $root 'upstream-island-checkpoint.properties'
            $checkpointDigest=Join-Path $root 'upstream-island-checkpoint.properties.sha256'
            if(-not(Test-Path -LiteralPath $checkpoint -PathType Leaf)-or-not(Test-Path -LiteralPath $checkpointDigest -PathType Leaf)-or
                (Get-FileHash -LiteralPath $checkpoint).Hash-ne([IO.File]::ReadAllText($checkpointDigest).Trim())){throw 'Immutable populated upstream checkpoint digest is missing or inconsistent.'}
            if([regex]::IsMatch([IO.File]::ReadAllText($configPath),'(?m)^pvc\s*:')){throw 'Original upstream phase unexpectedly added PVC configuration.'}
            # The fork's migration source is the actual normally stopped upstream
            # config. Copy evidence only; never rewrite the retained live config.
            [IO.File]::Copy($configPath,(Join-Path $root 'staged-before-config.yml'),$true)
            $before=Get-UpgradeState $root
            $sourceRecord.seed=[ordered]@{pluginSHA256=(Get-FileHash -LiteralPath (Join-Path $root 'plugins/SuperiorSkyblock2.jar')).Hash;
                configSHA256=(Get-FileHash -LiteralPath $configPath).Hash;checkpointSHA256=(Get-FileHash -LiteralPath $checkpoint).Hash;retainedState=$before}
            if($sourceRecord.seed.pluginSHA256-ne$upstreamExpectedSHA){throw 'Upstream artifact changed during seed phase.'}
            $seedTarget=[IO.Path]::GetFullPath((Join-Path $root 'plugins/SuperiorSkyblockUpstreamSeed.jar'))
            foreach($target in @($seedTarget,(Join-Path $root 'plugins/SuperiorSkyblock2.jar'),(Join-Path $root 'plugins/SuperiorSkyblockPVCProbe.jar'))){
                if(-not[IO.Path]::GetFullPath($target).StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or
                    ((Test-Path -LiteralPath $target)-and((Get-Item -LiteralPath $target).Attributes-band[IO.FileAttributes]::ReparsePoint))){throw 'Unsafe private plugin cutover target.'}
            }
            Copy-Item -LiteralPath $plugin -Destination (Join-Path $root 'plugins/SuperiorSkyblock2.jar') -Force
            Remove-Item -LiteralPath $seedTarget -Force
            Copy-Item -LiteralPath $probe -Destination (Join-Path $root 'plugins/SuperiorSkyblockPVCProbe.jar')
            $after=Get-UpgradeState $root
            Assert-UpgradeStateEqual $before $after
            $sourceRecord.cutover=[ordered]@{pluginSHA256=(Get-FileHash -LiteralPath (Join-Path $root 'plugins/SuperiorSkyblock2.jar')).Hash;
                probeSHA256=(Get-FileHash -LiteralPath (Join-Path $root 'plugins/SuperiorSkyblockPVCProbe.jar')).Hash;
                retainedStateUnchanged=$true;seedJarRemoved=(-not(Test-Path -LiteralPath $seedTarget))}
            [IO.File]::WriteAllText($sourcePath,($sourceRecord|ConvertTo-Json -Depth 10),$utf8)
            Run-Phase $root 'fork-upgrade' $java $port $lane.minecraft $mineflayer
            Run-Phase $root 'fork-upgrade-restart' $java $port $lane.minecraft $mineflayer
            $sourceRecord.final=[ordered]@{pluginSHA256=(Get-FileHash -LiteralPath (Join-Path $root 'plugins/SuperiorSkyblock2.jar')).Hash;
                probeSHA256=(Get-FileHash -LiteralPath (Join-Path $root 'plugins/SuperiorSkyblockPVCProbe.jar')).Hash;
                configSHA256=(Get-FileHash -LiteralPath $configPath).Hash;checkpointSHA256=(Get-FileHash -LiteralPath $checkpoint).Hash;
                phases=@('upstream-seed','fork-upgrade','fork-upgrade-restart');retainedState=(Get-UpgradeState $root)}
            if($sourceRecord.final.pluginSHA256-ne$sourceRecord.candidate.pluginSHA256-or
                $sourceRecord.final.probeSHA256-ne$sourceRecord.candidate.probeSHA256-or
                $sourceRecord.final.checkpointSHA256-ne$sourceRecord.seed.checkpointSHA256){throw 'Final upgrade artifact or immutable upstream checkpoint digest differs.'}
            [IO.File]::WriteAllText($sourcePath,($sourceRecord|ConvertTo-Json -Depth 10),$utf8)
            continue
        }
        Run-Phase $root 'initial' $java $port $lane.minecraft $mineflayer
        Run-Phase $root 'restart' $java $port $lane.minecraft $mineflayer
        if($Profile-eq'domain'){
            foreach($phase in @('cold-reset-intent','reset-restart','cold-transfer-intent','transfer-restart','cold-delete-intent','delete-restart',
                    'storage-reset-failure','storage-reset-restart','storage-reset-hold','storage-reset-crash-restart',
                    'storage-delete-failure','storage-delete-restart')){
                Run-Phase $root $phase $java $port $lane.minecraft $mineflayer
            }
        }
    }
} finally {if($null-ne$gate){$gate.Dispose();Write-Output 'SUPERIOR_RUNTIME_GATE_RELEASED'}}
