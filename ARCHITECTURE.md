# StoreGuard Hub — Safe Architecture

## 1. DMSS

```text
DMSS notification
  -> Android NotificationListenerService
  -> package == com.mm.android.DMSS 인 경우만 처리
  -> CctvEventEntity
  -> Room DB
```

알림 접근 권한은 Android 설정에서 사용자가 직접 허용합니다. StoreGuard 코드는 다른 앱의 알림을 DB에 저장하지 않습니다.

## 2. ANSI POS

```text
User taps "POS 수집 시작"
  -> Android MediaProjection consent dialog
  -> user approves
  -> foreground service + persistent notification
  -> in-memory frame
  -> on-device Korean OCR
  -> transaction-format parser
  -> PaymentEntity
  -> Room DB
  -> bitmap immediately recycled
```

### 안전 경계

- AccessibilityService 사용 금지
- 화면 터치/클릭 자동 주입 금지
- MediaProjection 승인 우회 금지
- 백그라운드에서 몰래 캡처 금지
- 원본 프레임 파일 저장 금지
- 전체 OCR 텍스트 저장 금지
- 세션 최대 15분
- 실행 중 지속 알림 표시
- 사용자가 즉시 중지 가능

## 3. 시간 동기화

`TimeMatcher`는 CCTV 이벤트와 POS 승인시각 사이의 시계 오프셋을 진단합니다.

이 결과는 **방문자와 결제자를 동일인이라고 확정하는 근거가 아닙니다.**

## 4. 다음 단계

실기기 수집이 확인된 뒤:

- 익명 Visitor session
- 입장/퇴장/결제구역 이벤트
- Visitor ↔ Payment 연결 상태
- 상품 취득/반납 후보
- 미결제 퇴장 후보
- 결제 후 추가취득 후보
- 다인 결제/부분결제처럼 모호한 사례의 `REVIEW` 상태

모든 이상징후는 사용자 검토 후보이며 절도 확정 판정으로 사용하지 않습니다.
