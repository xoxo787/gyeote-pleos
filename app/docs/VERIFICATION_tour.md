# 작업 H2 검증 — F2 '첫 3분 투어' 미션 (2026-10-26)

근거 파일: `tour-integration.json`(에뮬레이터 시나리오), `tour-vehicle.log`(앱 로그 `GyeoteVehicle`), `tour-build-verification.log`(빌드), `screens/tour/*.png`·`*.xml`(실제 화면과 UI 계층). H1 재실행 결과는 `handover-integration.json`에 덮어썼다.
에뮬레이터: Pleos Connect `emulator-5554`(2560×1440, 전기차 설정). 앱 상태는 별도 암호화 테스트 프로필 `tourH2`(H1 재실행은 `handoverH1`)를 써서 기존 `state.enc`를 건드리지 않았다.

## 합격 기준

| 기준 | expected | observed | passed |
|---|---|---|---|
| (a) 빌드·테스트·린트 | `test assembleDebug assembleRelease lintDebug` 통과, H1의 203개 유지 | BUILD SUCCESSFUL. Debug 216개·Release 216개(실패 0, 오류 0, 건너뜀 0; H1 203 + 새 13). 린트 Error 0, Warning 8(H1과 같은 버전 알림·16KB 정렬), 새 코드 경고 0 | true |
| (b) 에뮬레이터 시나리오 | 인계 카드 → [처음 타요] → 콘솔 `steering=-450` → `steering=450` → `brake=60` → `turn-signal?mode=BlinkLeft` → 완료 카드, 스크린샷 5장 | 16개 항목 모두 통과(아래 표). 스크린샷 5장. TTS는 요청 로그까지만(한국어 음성 없음) | true(TTS 발화 제외) |
| (c) D에서 투어 0, 금지어·호칭 0 | D에서 투어 화면 노출 0, 문구 grep 0 | D 시작·문 여닫기·D에서 재시작·투어 도중 D 전환 모두 투어 문구 0, `tour started` 로그 0. 정적 검색(`src/main` 전체, 투어 코드): 금지어 0, 투어 문구 호칭 0. 실행 화면 UI 계층 검색 0건 | true |
| (d) 기존 인계 카드·스냅샷 | `handover_integration.py` 재실행 통과 | 17개 중 15개 통과. 실패 2개는 H1 때와 같은 환경 한계(누적 12,345km로 되돌릴 수 없음, 한국어 TTS 음성 없음). `3-first-ride` 항목만 투어에 맞게 고침(아래) | true(H1과 같은 기지 실패 2건) |

## 에뮬레이터 시나리오 (`python app/tools/tour_integration.py`)

| 시나리오 | expected | observed | passed |
|---|---|---|---|
| d-start-no-tour | D로 앱 시작: 투어 없음 | 투어 로그·문구 0 | true |
| card-offers-first-ride | P + 운전석 문 열림→닫힘 → 카드에 [처음 타요] | 카드 표시, 버튼 있음 | true |
| enter-from-card | [처음 타요] → 스냅샷 저장(차 상태만) + 미션 1/3 | `tour started from=handover mode=RENTAL missions=STEERING+BRAKE+TURN_SIGNAL`, `handoverSnapshot saved … restored=true via=firstRide` | true |
| mission1-left-only | 콘솔 −450 → SDK +450 → 앱 −450, 아직 통과 아님 | `steeringSdk=450.0 steering=-450.0`, 게이지 `-450° · 왼쪽`, '왼쪽 끝 통과 / 오른쪽 끝 남음'(01) | true |
| mission1-pass | 콘솔 +450 → 앱 +450 → 미션 1 통과 | `tourMission passed=STEERING by=steering next=BRAKE` | true |
| mission2-screen | 체크 0.5초 뒤 미션 2만 | 02 | true |
| mission2-pass | `brake=60` → 통과 | `brakePressed=true` → `passed=BRAKE` | true |
| mission3-screen | 미션 3만 | 03 | true |
| mission3-pass | `BlinkLeft` → 통과·완료 | `passed=TURN_SIGNAL`, `tour completed passed=3` | true |
| done-card | "준비 끝났어요. 안전 운전하세요." + [시작하기] | 04 | true |
| tts-requests | 통과마다 "좋아요" 요청 | `tourSpeech kind=PASS, PASS, PASS+DONE`, `tts=queued`(실제 발화 불가) | true(요청만) |
| start-goes-home | [시작하기] → 홈 | `tour closed`, 홈 화면 | true |
| d-no-tour | D에서 문 여닫기·재시작해도 투어 없음 | 주행 카드 문장 1개만(05), 투어 로그 0 | true |
| replay-and-d-ends | 설정 [투어 다시 하기] → 시작, 도중 D → 즉시 종료, 다시 P여도 안 뜸 | `tour started from=settings`, `tour ended reason=gear=DRIVE passed=0 remaining=3`, D·P 모두 투어 문구 0 | true |
| modes-and-skip | 끄기면 버튼 숨김, 가족 공유는 2개, [건너뛰기] | 끄기: 버튼 없음. 가족 공유: `missions=STEERING+BRAKE`, '미션 1 / 2'. 건너뛰기: `tour skipped passed=0 remaining=2` → 완료 카드 '남은 미션은 건너뛰었어요' | true |
| ui-wording | 투어 화면 금지어·호칭 0 | 0건 | true |

스크린샷: `screens/tour/01-mission1-steering.png`, `02-mission2-brake.png`, `03-mission3-turn-signal.png`, `04-done.png`, `05-driving-no-tour.png`.
체크 애니메이션(0.5초)은 UI 계층 덤프가 더 오래 걸려 스크린샷에 담지 못했다. 동작은 통과 로그 뒤 다음 미션 화면이 뜨는 것으로 간접 확인.

## 동작 규칙(구현)
- 미션 1: 보정된 각도(콘솔 부호 기준) ≤ −400°와 ≥ +400°를 둘 다 찍으면 통과, 순서 무관. 큰 각도 숫자와 게이지(−540~+540°)를 보여 준다.
- 미션 2·3: 그 미션이 떠 있는 동안 새로 켜짐(꺼짐→켜짐)이 들어와야 통과. 미리 밟고 있던 브레이크는 한 번 떼었다 밟아야 한다.
- 기어가 P가 아니게 되거나 주행 제한이 켜지면 투어를 바로 끝내고 주행 화면으로. 다시 P가 돼도 투어는 자동으로 뜨지 않는다.
- 결과(통과·건너뜀·시간)는 저장하지 않는다. "이번 탑승에서 투어를 했다"는 메모리 플래그만 두고, 기어가 P를 떠나면 지운다. 같은 탑승에서는 카드에 [처음 타요]를 다시 내놓지 않는다.
- 모드별 미션: 렌터카·카셰어 3, 가족 공유 2(핸들·브레이크), 법인 3, 끄기 0(설정 버튼 숨김).

## 한계
- 실제 TTS 발화는 이 에뮬레이터에 한국어 음성이 없어 확인하지 못했다(요청 로그만).
- 핸들 각도의 −가 실제 왼쪽인지는 미확인이다. 게이지는 −를 왼쪽으로 표시하지만, 통과는 순서와 무관하게 양쪽 끝 둘 다로 판정하므로 결과에는 영향이 없다.
- 핸들각 지연(설계 09의 D4–5 산출물)은 측정하지 않았다.

## 2026-10-31 Passer 재검증 (작업 H6)
- 앱 표시 이름을 **Passer**로 바꾼 빌드(debug sha256 앞 12자리 `e993f8b6bac2`)로 다시 돌렸다. 단위 테스트 244개 통과(빌드·lint 결과는 `VERIFICATION_handover.md` 10-31 절).
- `tour_integration.py` **16개 중 16개 통과**. TTS는 이번에도 요청 로그로만 확인(`tts-requests`, 실제 발화 불가).
- `screens/tour/01~05`와 `tour-integration.json`을 새로 만들었고 상단 제목은 'Passer'(05 주행 화면은 제목 없는 화면).
