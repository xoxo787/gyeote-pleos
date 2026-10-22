# Pleos SDK 실측 노트 (2026-10-22)

공식 Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`, 로그인 불필요)에서 AAR을 받아 `javap`로 공개 API를 확인했다. 사용자가 42dot SDK 라이선스에 동의한 뒤 진행했다(docs/decisions.md).
전체 시그니처 덤프는 재배포 위험을 피하려고 저장소 밖 `<임시 폴더>/pleos-sdk-KWkrse\api_dump.txt`(2,922줄)에 두었다. **커밋하거나 공개하지 않는다.**

## 최신 버전 (maven-metadata 기준)
| 좌표 | 버전 | 주요 의존성 |
|---|---|---|
| `ai.pleos.playground:Vehicle` | 2.3.2 | vehicle-aidl 1.1.1, vehicle-data 1.1.11, kotlin 1.9.25, coroutines 1.6.3, gson |
| `ai.pleos.playground:TextToSpeech` | 3.3.1 | universal-access 0.6.0 |
| `ai.pleos.playground:SpeechToText` | 3.3.1 | universal-access 0.6.0 |
| `ai.pleos.playground:NaviHelper` | 3.0.5 | navi-aidl 3.0.1, navi-data 3.0.4 |
| `ai.pleos.playground:FusedLocation` | 1.1.3 | fused-location-aidl 0.0.5, fused-location-data 0.0.3 |

## 06(문서 조사)을 바로잡는 사실
| 06의 판단 | 실측 (Vehicle 2.3.2) | 영향 |
|---|---|---|
| 기어·시동 전용 API 없음 | **`Vehicle.getGear()`** → `getCurrentGearSelectionState`, `registerGearSelection(GearSelectionListener)`. 값: `VehicleGear.GEAR_PARK / DRIVE / REVERSE / NEUTRAL / UNKNOWN` | 출발/도착 판정에 **기어 P↔D**를 주 신호로 쓸 수 있다(도어·브레이크는 보조). Virtual Console에 Gear 카드도 있다 |
| 타이어는 저압 여부만, 콜백 없음 | **`Tire.getTirePressure(WheelArea)` + `registerTirePressure(WheelArea, TirePressureListener)`** — `onTirePressureUpdated(Float)`. `getLowTirePressure`도 Float 반환 | 공기압 **수치**로 안내 가능. 단위는 [미확인] → 에뮬레이터에서 실측 |
| 텔테일 종류 [검색요약] | `TelltaleType` 상수 96개(TPMS, ENGINE, ENGINE_OIL_PRESSURE, ENGINE_TEMPERATURE, ABS, AIRBAG, SEAT_BELT, LOW_FUEL, MASTER_WARNING 등 확인). `TelltaleData(type, state, color:int, priority:int, warningCount:int)` | 사전 키를 SDK 상수명과 맞춘다 |
| Safety 추가 기능 | 엔진오일·브레이크액·변속기오일 레벨(`OilLevelState`) 조회·콜백, 워셔액 부족, 안전벨트 미착용, 에어백 | 정비 안내에 '오일 레벨'을 더할 수 있다 |

## 에뮬레이터 실측 (작업 J, 2026-10-22, 근거 `app/docs/VERIFICATION_car.md`)
- **`PRIVATE_CAR_SAFETY` 필요 API**: `registerTelltales`/`unregisterTelltales`, `checkTirePressureCapabilities`, `getTirePressure`, `registerTirePressure`. 일반 `CAR_SAFETY`로는 SecurityException. 공개 가이드상 **앱마켓 사전 승인 필요** → 출시 빌드는 공개 `getTelltalesInfo` 3초 폴링으로 대체. 설명회 1순위 질문.
- 타이어 공기압 수치: Pleos Connect 2.1.0 에뮬레이터는 `ERROR_NOT_SUPPORTED_PROPERTY`(Console 입력 단위는 kPa, SDK 수신 단위는 미확인). TPMS 경고등은 동작.
- 기어·도어·TPMS 텔테일: Virtual Console 변경이 앱에 실제 반영됨.
- TTS/STT 3.3.1 `ON_DEVICE`: 키 없이 초기화는 되지만 둘 다 `ON_DEVICE_ENGINE_ERROR`. 원인 미확인.

## 호출 모델
- 모든 조회는 `get…(onComplete: (T)->Unit, onFailed: (Exception)->Unit)` 형태의 Kotlin 람다, 변경 감지는 `register…(Listener)` / `unregister…`.
- `Vehicle(context).initialize()` 후 도메인 객체를 얻고, 끝나면 `release()`.
- STT에는 `stt.data.Speaker`, `CallApi`, `AmbientAiApi` 같은 클래스가 있다 — 화자 정보 제공 여부는 [미확인](06의 '화자 식별 없음'을 재검토할 대상).
