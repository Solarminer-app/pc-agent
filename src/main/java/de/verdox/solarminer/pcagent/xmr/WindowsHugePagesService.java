package de.verdox.solarminer.pcagent.xmr;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manages the Windows account right XMRig needs for large pages. */
@Service
public class WindowsHugePagesService {
    private static final Pattern SID = Pattern.compile("S-1-(?:[0-9]+-){1,14}[0-9]+");
    private static final String ELEVATED_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            [StructLayout(LayoutKind.Sequential)] public struct LSA_OBJECT_ATTRIBUTES { public uint Length; public IntPtr RootDirectory; public IntPtr ObjectName; public uint Attributes; public IntPtr SecurityDescriptor; public IntPtr SecurityQualityOfService; }
            [StructLayout(LayoutKind.Sequential)] public struct LSA_UNICODE_STRING { public ushort Length; public ushort MaximumLength; public IntPtr Buffer; }
            public static class SolarMinerHugePages {
              [DllImport("advapi32.dll", CharSet=CharSet.Unicode)] static extern bool ConvertStringSidToSid(string s, out IntPtr p);
              [DllImport("advapi32.dll")] static extern uint LsaOpenPolicy(IntPtr system, ref LSA_OBJECT_ATTRIBUTES attr, uint access, out IntPtr policy);
              [DllImport("advapi32.dll")] static extern uint LsaAddAccountRights(IntPtr policy, IntPtr sid, ref LSA_UNICODE_STRING rights, uint count);
            [DllImport("advapi32.dll")] static extern uint LsaRemoveAccountRights(IntPtr policy, IntPtr sid, bool all, ref LSA_UNICODE_STRING rights, uint count);
            [DllImport("advapi32.dll")] static extern uint LsaEnumerateAccountRights(IntPtr policy, IntPtr sid, out IntPtr rights, out uint count);
            [DllImport("advapi32.dll")] static extern uint LsaFreeMemory(IntPtr buffer);
              [DllImport("advapi32.dll")] static extern uint LsaClose(IntPtr policy);
              [DllImport("advapi32.dll")] static extern uint LsaNtStatusToWinError(uint status);
              [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
              public static int Grant(string accountSid) {
                IntPtr sid=IntPtr.Zero, policy=IntPtr.Zero, name=IntPtr.Zero;
                try {
                  if (!ConvertStringSidToSid(accountSid, out sid)) return Marshal.GetLastWin32Error();
                  var attr = new LSA_OBJECT_ATTRIBUTES(); attr.Length=(uint)Marshal.SizeOf(typeof(LSA_OBJECT_ATTRIBUTES));
                  uint status=LsaOpenPolicy(IntPtr.Zero, ref attr, 0x810, out policy);
                  if (status != 0) return (int)LsaNtStatusToWinError(status);
                  name=Marshal.StringToHGlobalUni("SeLockMemoryPrivilege");
                  var right=new LSA_UNICODE_STRING(); right.Buffer=name; right.Length=(ushort)("SeLockMemoryPrivilege".Length*2); right.MaximumLength=(ushort)(right.Length+2);
                  status=LsaAddAccountRights(policy, sid, ref right, 1);
                  return status == 0 ? 0 : (int)LsaNtStatusToWinError(status);
                } finally { if(name!=IntPtr.Zero) Marshal.FreeHGlobal(name); if(policy!=IntPtr.Zero) LsaClose(policy); if(sid!=IntPtr.Zero) LocalFree(sid); }
              }
              public static bool HasRight(string accountSid) {
                IntPtr sid=IntPtr.Zero, policy=IntPtr.Zero, rights=IntPtr.Zero;
                try {
                  if (!ConvertStringSidToSid(accountSid, out sid)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                  var attr = new LSA_OBJECT_ATTRIBUTES(); attr.Length=(uint)Marshal.SizeOf(typeof(LSA_OBJECT_ATTRIBUTES));
                  uint status=LsaOpenPolicy(IntPtr.Zero, ref attr, 0x800, out policy);
                  if (status != 0) throw new System.ComponentModel.Win32Exception((int)LsaNtStatusToWinError(status));
                  uint count; status=LsaEnumerateAccountRights(policy, sid, out rights, out count);
                  if (status == 0xC0000034) return false;
                  if (status != 0) throw new System.ComponentModel.Win32Exception((int)LsaNtStatusToWinError(status));
                  int size=Marshal.SizeOf(typeof(LSA_UNICODE_STRING));
                  for (int i=0; i<count; i++) {
                    var item=(LSA_UNICODE_STRING)Marshal.PtrToStructure(IntPtr.Add(rights, i*size), typeof(LSA_UNICODE_STRING));
                    if (Marshal.PtrToStringUni(item.Buffer, item.Length/2).Equals("SeLockMemoryPrivilege", StringComparison.OrdinalIgnoreCase)) return true;
                  }
                  return false;
                } finally { if(rights!=IntPtr.Zero) LsaFreeMemory(rights); if(policy!=IntPtr.Zero) LsaClose(policy); if(sid!=IntPtr.Zero) LocalFree(sid); }
              }
              public static int Remove(string accountSid) {
                IntPtr sid=IntPtr.Zero, policy=IntPtr.Zero, name=IntPtr.Zero;
                try {
                  if (!ConvertStringSidToSid(accountSid, out sid)) return Marshal.GetLastWin32Error();
                  var attr = new LSA_OBJECT_ATTRIBUTES(); attr.Length=(uint)Marshal.SizeOf(typeof(LSA_OBJECT_ATTRIBUTES));
                  uint status=LsaOpenPolicy(IntPtr.Zero, ref attr, 0x800, out policy);
                  if (status != 0) return (int)LsaNtStatusToWinError(status);
                  name=Marshal.StringToHGlobalUni("SeLockMemoryPrivilege");
                  var right=new LSA_UNICODE_STRING(); right.Buffer=name; right.Length=(ushort)("SeLockMemoryPrivilege".Length*2); right.MaximumLength=(ushort)(right.Length+2);
                  status=LsaRemoveAccountRights(policy, sid, false, ref right, 1);
                  return status == 0 || status == 0xC0000034 ? 0 : (int)LsaNtStatusToWinError(status);
                } finally { if(name!=IntPtr.Zero) Marshal.FreeHGlobal(name); if(policy!=IntPtr.Zero) LsaClose(policy); if(sid!=IntPtr.Zero) LocalFree(sid); }
              }
            }
            '@
            $sid = $args[0]
            $action = $args[1]
            $marker = $args[2]
            if ($action -eq 'enable') {
              $alreadyAssigned = [SolarMinerHugePages]::HasRight($sid)
              $result = [SolarMinerHugePages]::Grant($sid)
              if ($result -ne 0) { Write-Error "Windows could not grant SeLockMemoryPrivilege (error $result)"; exit 1 }
              Set-Content -LiteralPath $marker -Value $(if ($alreadyAssigned) { 'preexisting' } else { 'added' }) -NoNewline
              exit 0
            }
            if ($action -eq 'disable') {
              if ((Test-Path -LiteralPath $marker) -and (Get-Content -LiteralPath $marker -Raw) -eq 'added') {
                $result = [SolarMinerHugePages]::Remove($sid)
                if ($result -ne 0) { Write-Error "Windows could not remove SeLockMemoryPrivilege (error $result)"; exit 1 }
              }
              Remove-Item -LiteralPath $marker -Force -ErrorAction SilentlyContinue
              exit 0
            }
            Write-Error 'Invalid action'; exit 1
            """;

    public boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** True after the current logon token has received the right; a fresh logon is required after grant. */
    public boolean availableInCurrentSession() {
        if (!isWindows()) return true;
        try {
            Process process = new ProcessBuilder("whoami.exe", "/priv", "/fo", "csv", "/nh")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && output.toLowerCase(Locale.ROOT).contains("selockmemoryprivilege");
        } catch (Exception ignored) { return false; }
    }

    /** Adds/removes the right for this account only; Windows UAC asks before policy changes. */
    public void configureForCurrentUser(boolean enabled) throws IOException, InterruptedException {
        if (!isWindows()) return;
        String sid = currentUserSid();
        Path marker = Path.of(System.getProperty("user.home"), ".solarminer", "security", "hugepages-" + sid + ".state");
        if (enabled && availableInCurrentSession()) return;
        if (!enabled && !Files.isRegularFile(marker)) return;
        Path script = Files.createTempFile("solarminer-hugepages-", ".ps1");
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(script, ELEVATED_SCRIPT, StandardCharsets.UTF_8);
            String scriptPath = script.toAbsolutePath().toString().replace("'", "''");
            String markerPath = marker.toAbsolutePath().toString().replace("'", "''");
            String action = enabled ? "enable" : "disable";
            String command = "$ErrorActionPreference='Stop'; try { $p=Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','\""
                    + scriptPath + "\"','" + sid + "','" + action + "','\"" + markerPath + "\"') -Verb RunAs -Wait -PassThru; exit $p.ExitCode } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1223 }";
            Process launcher = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).start();
            if (!launcher.waitFor(3, TimeUnit.MINUTES)) {
                launcher.destroyForcibly();
                throw new IOException("Windows-UAC-Freigabe für Huge Pages hat zu lange gedauert");
            }
            String output = new String(launcher.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (launcher.exitValue() != 0)
                throw new IOException(launcher.exitValue() == 1223 ? "Windows-UAC-Freigabe wurde abgelehnt" :
                        output.isBlank() ? "Windows konnte die Huge-Pages-Berechtigung nicht ändern" : output);
        } finally {
            Files.deleteIfExists(script);
        }
    }

    private String currentUserSid() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("whoami.exe", "/user", "/fo", "csv", "/nh")
                .redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Windows-Benutzer konnte nicht ermittelt werden"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = SID.matcher(output);
        if (process.exitValue() != 0 || !matcher.find()) throw new IOException("Windows-Benutzer-SID konnte nicht ermittelt werden");
        return matcher.group();
    }
}
