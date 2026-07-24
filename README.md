# GMS Fast Pair LSPosed Diagnostics

An LSPosed diagnostic module for tracing why Google Play services suppresses
the Fast Pair half-sheet for a certified tracker.

The initial target is Google Play services
`26.26.34 (260400-945364269)`. Its relevant obfuscated classes are:

- `drgg`: initial Fast Pair half-sheet decision manager
- `dqqi`: eligibility and suppression predicates used by `drgg`
- `dreq`: the scanned-device request
- `dpyf`: `InitialPairingDeviceChecker`

## Important finding

`InitialPairingDeviceChecker` is not the source of
`DEVICE_NOT_SUPPORTED`. In this build, `dpyf` performs cached-device/address
checks after a scan has already passed the half-sheet eligibility decision.
The final scan result is produced by `drgg.g(dreq)`.

This first version is deliberately diagnostic-only: it logs return values and
the final decision stack, but does not modify Google Play services behavior.

## Build

```text
gradle :app:assembleDebug
```

GitHub Actions also builds the APK and uploads it as a workflow artifact.

## Use

1. Install the APK.
2. Enable it in LSPosed.
3. Scope it only to **Google Play services** (`com.google.android.gms`).
4. Reboot, or fully stop and restart Google Play services.
5. Clear logs and put the tracker into pairing mode.
6. Capture the diagnostic output:

```text
adb logcat -c
adb logcat -v time | grep GmsFastPairDiag
```

On Windows:

```powershell
adb logcat -v time | Select-String GmsFastPairDiag
```

The useful lines include:

- `dqqi#... result=...`: individual eligibility predicates
- `drgg#g... result=...`: final half-sheet result
- `decision stack`: caller chain for the final decision
- `dpyf#...`: cached-device checker timing

Bluetooth MAC addresses are redacted by the module.

## Compatibility

Google Play services uses obfuscated class names and may change them with every
update. A future GMS version may require updating the class and method map.

## Next step

After identifying the exact predicate that produces
`DEVICE_NOT_SUPPORTED`, add a narrowly scoped, opt-in bypass for model ID
`15D23E`. Do not replace `drgg.g()` itself: that method also launches the
half-sheet, so returning `SUCCESS` from a replacement would skip the launch.
