# TabletBridge wedge: types barcode scans from the Q90 tablet into whatever
# window has focus on this PC (e.g. Mendure DME) as a fast keystroke burst
# followed by Enter - identical to a USB keyboard-wedge scanner.
#
# Usage:  powershell -ExecutionPolicy Bypass -File wedge.ps1 [-Passcode 123456]
# Requires: adb (Android platform-tools) on PATH and the tablet connected via USB.

param(
  [string]$Passcode = $(if ($env:TB_PASSCODE) { $env:TB_PASSCODE } else { '' }),
  [string]$Host_ = ''   # optional: tablet's Wi-Fi IP (e.g. 192.168.43.1) - skips adb
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class WedgeKeys {
  [DllImport("user32.dll")]
  public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

  public static void SendKey(byte vk, bool shift) {
    if (shift) keybd_event(0x10, 0, 0, UIntPtr.Zero);
    keybd_event(vk, 0, 0, UIntPtr.Zero);
    keybd_event(vk, 0, 2, UIntPtr.Zero);
    if (shift) keybd_event(0x10, 0, 2, UIntPtr.Zero);
  }
}
"@

# US keyboard mapping for printable ASCII -> virtual key code (+shift flag)
$vkMap = @{
  'a'=@(0x41,$false);'b'=@(0x42,$false);'c'=@(0x43,$false);'d'=@(0x44,$false)
  'e'=@(0x45,$false);'f'=@(0x46,$false);'g'=@(0x47,$false);'h'=@(0x48,$false)
  'i'=@(0x49,$false);'j'=@(0x4A,$false);'k'=@(0x4B,$false);'l'=@(0x4C,$false)
  'm'=@(0x4D,$false);'n'=@(0x4E,$false);'o'=@(0x4F,$false);'p'=@(0x50,$false)
  'q'=@(0x51,$false);'r'=@(0x52,$false);'s'=@(0x53,$false);'t'=@(0x54,$false)
  'u'=@(0x55,$false);'v'=@(0x56,$false);'w'=@(0x57,$false);'x'=@(0x58,$false)
  'y'=@(0x59,$false);'z'=@(0x5A,$false)
  '0'=@(0x30,$false);'1'=@(0x31,$false);'2'=@(0x32,$false);'3'=@(0x33,$false)
  '4'=@(0x34,$false);'5'=@(0x35,$false);'6'=@(0x36,$false);'7'=@(0x37,$false)
  '8'=@(0x38,$false);'9'=@(0x39,$false)
  ')'=@(0x30,$true);'!'=@(0x31,$true);'@'=@(0x32,$true);'#'=@(0x33,$true)
  '$'=@(0x34,$true);'%'=@(0x35,$true);'^'=@(0x36,$true);'&'=@(0x37,$true)
  '*'=@(0x38,$true);'('=@(0x39,$true)
  '-'=@(0xBD,$false);'_'=@(0xBD,$true)
  '='=@(0xBB,$false);'+'=@(0xBB,$true)
  '['=@(0xDB,$false);'{'=@(0xDB,$true)
  ']'=@(0xDD,$false);'}'=@(0xDD,$true)
  ';'=@(0xBA,$false);':'=@(0xBA,$true)
  "'"=@(0xDE,$false);'"'=@(0xDE,$true)
  ','=@(0xBC,$false);'<'=@(0xBC,$true)
  '.'=@(0xBE,$false);'>'=@(0xBE,$true)
  '/'=@(0xBF,$false);'?'=@(0xBF,$true)
  '\'=@(0xDC,$false);'|'=@(0xDC,$true)
  '`'=@(0xC0,$false);'~'=@(0xC0,$true)
}

function Type-Code([string]$code) {
  foreach ($ch in $code.ToCharArray()) {
    $s = [string]$ch
    if ($vkMap.ContainsKey($s)) {
      [WedgeKeys]::SendKey([byte]$vkMap[$s][0], [bool]$vkMap[$s][1])
    }
    Start-Sleep -Milliseconds 15
  }
  # Enter suffix (VK_RETURN), required by Mendure DME's wedge detector
  [WedgeKeys]::SendKey([byte]0x0D, $false)
}

function Send-WsText($ws, [string]$text) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
  $seg = [ArraySegment[byte]]::new($bytes)
  $null = $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [CancellationToken]::None).GetAwaiter().GetResult()
}

function Recv-WsText($ws) {
  $buf = New-Object byte[] 65536
  $ms = New-Object System.IO.MemoryStream
  while ($true) {
    $seg = [ArraySegment[byte]]::new($buf)
    $res = $ws.ReceiveAsync($seg, [CancellationToken]::None).GetAwaiter().GetResult()
    if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
      return $null
    }
    $ms.Write($buf, 0, $res.Count)
    if ($res.EndOfMessage) { break }
  }
  [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

if (-not $Passcode) {
  $Passcode = Read-Host 'Enter the passcode shown in the TabletBridge app'
}

if ($Host_) {
  $target = "ws://${Host_}:8787/ws"
  Write-Host "[1/3] direct Wi-Fi mode -> ${Host_}:8787"
} else {
  $target = "ws://127.0.0.1:8787/ws"
  Write-Host '[1/3] adb forward tcp:8787 tcp:8787'
  adb forward tcp:8787 tcp:8787
  if ($LASTEXITCODE -ne 0) {
    Write-Error 'adb failed. Install platform-tools (https://developer.android.com/tools/releases/platform-tools) and connect the tablet via USB.'
    exit 1
  }
}

Write-Host "[2/3] connecting to $target"
$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$uri = [Uri]$target
$cts = [CancellationTokenSource]::new()
$ws.ConnectAsync($uri, $cts.Token).GetAwaiter().GetResult()

Send-WsText $ws '{"type":"hello"}'
Write-Host ('[3/3] connected: ' + (Recv-WsText $ws))
Write-Host 'Wedge active. Scan a barcode with the tablet camera; it will be typed into the focused window (End with Ctrl+C to quit).'
Write-Host 'Tip: click into the Mendure DME Home #scan field (or any non-text view) before scanning.'

while ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
  $msg = Recv-WsText $ws
  if ($null -eq $msg) { break }
  try { $json = $msg | ConvertFrom-Json } catch { continue }
  if ($json.type -eq 'barcode') {
    $value = [string]$json.value
    Write-Host ("SCAN {0} [{1}] -> typing" -f $value, $json.format)
    Type-Code $value
  }
}
Write-Host 'disconnected'
$ws.Dispose()
