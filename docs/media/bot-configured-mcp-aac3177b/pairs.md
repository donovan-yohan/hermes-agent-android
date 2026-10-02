# Paired gallery

Original PNG links and inline native images. These are behavioral comparisons, not visual-equivalence acceptance. Desktop JSON remains observational.

## mcp-disabled / Desktop disabled

Both start off; inventories and layouts differ.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-disabled light](android/mcp-disabled/light/reference.png) | ![Desktop disabled light](desktop/disabled-light.png) |
| [Canonical-schema receipt](android/mcp-disabled/light/contract.json) | [Original observation](desktop/disabled-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-disabled dark](android/mcp-disabled/dark/reference.png) | ![Desktop disabled dark](desktop/disabled-dark.png) |
| [Canonical-schema receipt](android/mcp-disabled/dark/contract.json) | [Original observation](desktop/disabled-dark.json) |

## mcp-pending / Desktop pending

Android named write and disabled busy gate; Desktop whole-map write, switch still enabled.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-pending light](android/mcp-pending/light/reference.png) | ![Desktop pending light](desktop/pending-light.png) |
| [Canonical-schema receipt](android/mcp-pending/light/contract.json) | [Original observation](desktop/pending-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-pending dark](android/mcp-pending/dark/reference.png) | ![Desktop pending dark](desktop/pending-dark.png) |
| [Canonical-schema receipt](android/mcp-pending/dark/contract.json) | [Original observation](desktop/pending-dark.json) |

## mcp-saved / Desktop saved

Android verified readback/future-session notice; Desktop committed map, reload send and synthetic probe.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-saved light](android/mcp-saved/light/reference.png) | ![Desktop saved light](desktop/saved-light.png) |
| [Canonical-schema receipt](android/mcp-saved/light/contract.json) | [Original observation](desktop/saved-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-saved dark](android/mcp-saved/dark/reference.png) | ![Desktop saved dark](desktop/saved-dark.png) |
| [Canonical-schema receipt](android/mcp-saved/dark/contract.json) | [Original observation](desktop/saved-dark.json) |

## mcp-reopened / Desktop reopened

Android VM close/open staging; Desktop native outer Cancel then reopen.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-reopened light](android/mcp-reopened/light/reference.png) | ![Desktop reopened light](desktop/reopened-light.png) |
| [Canonical-schema receipt](android/mcp-reopened/light/contract.json) | [Original observation](desktop/reopened-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-reopened dark](android/mcp-reopened/dark/reference.png) | ![Desktop reopened dark](desktop/reopened-dark.png) |
| [Canonical-schema receipt](android/mcp-reopened/dark/contract.json) | [Original observation](desktop/reopened-dark.json) |

## mcp-empty / Desktop empty

Successful empty inventories.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-empty light](android/mcp-empty/light/reference.png) | ![Desktop empty light](desktop/empty-light.png) |
| [Canonical-schema receipt](android/mcp-empty/light/contract.json) | [Original observation](desktop/empty-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-empty dark](android/mcp-empty/dark/reference.png) | ![Desktop empty dark](desktop/empty-dark.png) |
| [Canonical-schema receipt](android/mcp-empty/dark/contract.json) | [Original observation](desktop/empty-dark.json) |

## mcp-loading / Desktop loading

Android pending read text; Desktop pending config read skeleton.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-loading light](android/mcp-loading/light/reference.png) | ![Desktop loading light](desktop/loading-light.png) |
| [Canonical-schema receipt](android/mcp-loading/light/contract.json) | [Original observation](desktop/loading-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-loading dark](android/mcp-loading/dark/reference.png) | ![Desktop loading dark](desktop/loading-dark.png) |
| [Canonical-schema receipt](android/mcp-loading/dark/contract.json) | [Original observation](desktop/loading-dark.json) |

## mcp-error / Desktop read-refused-settled

Refusal divergence: Android refusal copy; Desktop settles into empty copy after retries.

### light

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-error light](android/mcp-error/light/reference.png) | ![Desktop read-refused-settled light](desktop/read-refused-settled-light.png) |
| [Canonical-schema receipt](android/mcp-error/light/contract.json) | [Original observation](desktop/read-refused-settled-light.json) |

### dark

| Android | Actual Desktop Connectors |
|---|---|
| ![Android mcp-error dark](android/mcp-error/dark/reference.png) | ![Desktop read-refused-settled dark](desktop/read-refused-settled-dark.png) |
| [Canonical-schema receipt](android/mcp-error/dark/contract.json) | [Original observation](desktop/read-refused-settled-dark.json) |

## Android-only observations

No Desktop counterpart is asserted for these states.

- **mcp-loaded**: [light](android/mcp-loaded/light/reference.png), [dark](android/mcp-loaded/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
- **mcp-plugin**: [light](android/mcp-plugin/light/reference.png), [dark](android/mcp-plugin/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
- **mcp-unknown**: [light](android/mcp-unknown/light/reference.png), [dark](android/mcp-unknown/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
- **mcp-unavailable**: [light](android/mcp-unavailable/light/reference.png), [dark](android/mcp-unavailable/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
- **mcp-refused**: [light](android/mcp-refused/light/reference.png), [dark](android/mcp-refused/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
- **mcp-unconfirmed**: [light](android/mcp-unconfirmed/light/reference.png), [dark](android/mcp-unconfirmed/dark/reference.png); adjacent `contract.json` files retain both telemetry brackets.
