# 시연 영상 — 설명 그림(모션그래픽)

충전오락관과 Passer, 두 앱의 시연 영상을 같은 틀로 만듭니다. 시연 영상의 설명 그림은 영상 편집 프로그램이 아니라 코드로 그리고 렌더링했습니다([Remotion](https://www.remotion.dev/), React). 실제 앱 화면은 에뮬레이터 화면 녹화를 잘라 넣었고, 영상 안에서 "설명용 그림"과 "에뮬레이터 화면 녹화"를 구분해 표시했습니다.

## 구성

| 파일 | 내용 |
|---|---|
| `src/scenes.tsx` | 충전오락관: 여덟 장면과 전체 영상(`Film`) |
| `src/passer.tsx` | Passer: 여덟 장면과 전체 영상(`PasserFilm`). 앱 카드는 Passer의 색과 실제 화면 문구로 그렸습니다 |
| `src/parts.tsx` | 공통 부품: 장면 틀, 앱 화면 재현, 충전기, 케이블 |
| `src/CarSide.tsx` | 차 옆모습 그림(SVG) |
| `src/kit2.tsx` | 색·글꼴·그림자, 카메라, 글자 등장 효과 |
| `public/clips/` | 에뮬레이터 화면 녹화에서 자른 네 구간(통합 검증 스크립트가 Virtual Console을 조작하는 동안 녹화) |
| `public/passer/` | Passer의 에뮬레이터 실제 화면 캡처 네 장(`app/docs/screens/`의 검증 캡처를 줄인 것) |
| `public/fonts/` | Pretendard, Playfair Display (둘 다 SIL Open Font License) |

## 렌더링

```
npm install
npx remotion render src/index.ts Film out/film.mp4
npx remotion render src/index.ts PasserFilm out/passer-film.mp4
```

Node 20 이상. 결과물(`out/`)은 저장소에 넣지 않습니다.
