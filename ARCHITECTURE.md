# v0.1 Architecture

## Data flow

```text
DMSS notification
  -> NotificationListenerService
  -> CctvEventEntity
  -> Room DB

ANSI POS sales list screen
  -> AccessibilityService
  -> UI text nodes + screen bounds
  -> row grouping / parser
  -> PaymentEntity
  -> Room DB

CCTV event + POS payment
  -> TimeMatcher (diagnostic only)
  -> clock-offset suggestion
```

## Important boundary

`TimeMatcher` is NOT a person/payment identity matcher. It only diagnoses whether the two apps' clocks can be aligned. Visitor-payment 1:1 association must use actual visitor tracking/payment-zone events in a later version.

## v0.2 target

- Camera/NVR capability survey (prefer RTSP/ONVIF/local stream when legitimately available)
- Anonymous per-visit track IDs
- Entry/exit zones and payment-zone events
- Visitor ↔ Payment relation with ambiguous state preserved

## v0.3 target

- Product interaction events independent of payment relation
- anomaly categories: no-payment exit candidate, payment-then-additional-pick candidate, ambiguous group payment, etc.
- no automatic accusation; all anomaly outputs remain review candidates
