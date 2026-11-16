# Device facts (verified with nRF Connect)

Source: nRF Connect screenshots, 2026-11-16 (Polar: advertising data only; KICKR: advertising + connected GATT session).
Values marked [ ] are still open; they are read by the app at runtime or tested in Phase 1.

## Polar H9

| Item | Value |
|---|---|
| Advertised name | `Polar H9 <serial>` |
| Device type | LE only |
| Advertised 16-bit services (incomplete list) | `0x180D` (Heart Rate), `0xFEEE` (Polar Electro, proprietary) |
| Manufacturer data | Company `0x006B` (Polar Electro Oy) |
| Tx power level | 4 dBm |
| Bonding | Already bonded with the phone (Android bonding). The app must work with both bonded and unbonded devices |
| Battery Service 0x180F present | Yes (read by the app) |
| Model / firmware | H9 / 5.0.0 |
| HR Measurement flags / RR intervals | Not needed in advance: the parser covers every flag combination in the spec. This is checked in-app in Phase 1 (on the device only, no HR values in prompts) |
| Second BLE connection possible while watch is connected? | [ ] (test in Phase 1) |

**App consequences:** scan filter on service `0x180D`; show the advertised name.

## Wahoo KICKR Core

| Item | Value |
|---|---|
| Advertised name | `KICKR CORE <4 hex chars>` |
| Device type | LE only |
| Advertised 16-bit services (complete list) | `0x1818` (Cycling Power), `0x1826` (Fitness Machine / FTMS) |
| FTMS service data | `0x01 2000` → flags `0x01` = *Fitness Machine Available*; machine type `0x0020` (LE) = bit 5 = **Indoor Bike** |
| Other service data | 128-bit UUID `00000001-19ca-4651-86e5-fa29dcdd09d1`, data `0x01`. Proprietary, probably Wahoo; not used |
| Firmware version | 1.5.36 (read by the app, 2026-11-16) |
| Bonding | Not bonded; none needed (works without pairing) |

### GATT services (connected)

| Service | UUID | Use |
|---|---|---|
| Generic Access / Generic Attribute | `0x1800` / `0x1801` | – |
| Device Information | `0x180A` | Firmware/model display |
| **Fitness Machine (FTMS)** | `0x1826` | **Data + ERG control** |
| Cycling Power | `0x1818` | Fallback only |
| User Data | `0x181C` (includes *Weight*) | Not needed for ERG |
| Wahoo proprietary | `a026ee01-…`, `a026ee06-…`, `a026ee0b-…` (base `-0a7d-4ab3-97fa-f1500f9feb8b`) | Not used |
| Unknown proprietary | `00000001-19ca-4651-86e5-fa29dcdd09d1` | Not used |

### FTMS characteristics

| Characteristic | UUID | Properties |
|---|---|---|
| Fitness Machine Feature | `0x2ACC` | READ |
| Training Status | `0x2AD3` | NOTIFY, READ |
| Fitness Machine Control Point | `0x2AD9` | WRITE, INDICATE |
| Fitness Machine Status | `0x2ADA` | NOTIFY |
| **Indoor Bike Data** | `0x2AD2` | NOTIFY |
| Supported Resistance Level Range | `0x2AD6` | READ |
| Supported Power Range | `0x2AD8` | READ |

### Indoor Bike Data sample (pedalling)

nRF Connect decoded one notification as: speed **30.43 km/h**, cadence **93.0 rpm**, power **282 W**. No other fields were present.

- **Cadence comes from the KICKR, confirmed.** No extra sensor needed.
- Fields present: speed (bit 0 = 0), cadence (bit 2), power (bit 6), so the flags are probably `0x0044`. The equivalent payload `44 00 E3 0B BA 00 1A 01` is covered by a unit test (rebuilt from the decoded values; the raw hex was not captured).
- Values read by the app (Settings → Trainer, 2026-11-16):
  - Fitness Machine Feature: `03 40 00 00 0C 60 00 00`
    - Machine features `0x00004003`: average speed (bit 0), **cadence** (bit 1), **power measurement** (bit 14)
    - Target settings `0x0000600C`: resistance (bit 2), **power target / ERG** (bit 3), indoor bike simulation (bit 13), wheel circumference (bit 14)
  - Supported Power Range: **0–2000 W, step 1 W**
- ERG test (set target, release) verified on the real trainer.

**App consequences:** scan filter on service `0x1826`. The FTMS service data (machine type = Indoor Bike) can confirm in the scan list that a device is a controllable bike trainer. Cycling Power `0x1818` is available as a fallback.

## Remaining open points

- Polar H9: whether a second connection works while a watch is connected (not needed so far)
