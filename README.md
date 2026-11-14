# 사이 · Hyundai Sai

출근길에 오늘의 마음을 씨앗으로 심고, 퇴근 후 기록한 감정을 꽃으로 남기는 차량용 일기 앱입니다.

## Android Studio에서 실행

1. 이 저장소의 최상위 폴더를 Android Studio에서 엽니다.
2. Gradle JDK 17, SDK Platform 35, Build Tools 35.0.0으로 동기화합니다.
3. Android 또는 Pleos Connect API 34 에뮬레이터를 실행하고 `app` 구성을 설치합니다.
4. 최초 정보 입력 후 씨앗을 심고 낮·밤과 날씨를 고릅니다. 홈 날씨 아이콘에서 시연 설정을 다시 바꿀 수 있습니다.

이미 클론했다면 `git switch main`과 `git pull --ff-only origin main`을 실행합니다.
Windows는 `.\gradlew.bat assembleDebug`, macOS/Linux는 `./gradlew assembleDebug`로 APK를 만들 수 있습니다.
빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다.

## Pleos Connect 에뮬레이터

기존 Pleos 에뮬레이터가 있다면 그대로 선택합니다. 새로 설치할 때 SDK Manager → SDK Update Sites에
아래 주소를 추가하고 API 34 Pleos 시스템 이미지를 설치합니다.

- Windows/Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
- macOS: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
- Device Manager에서 Automotive / Pleos Connect 장치 생성. 공식 가이드의 RAM 4 GB, Cold boot 설정 참고.

## 프로토타입 기능

| 행 | 구현 |
|---|---|
| 01 | 프로필, 씨앗 심기, 낮·밤 날씨 선택 |
| 02 · 05 | 아침·저녁 홈, 날씨별 배경 8종, 음성 또는 텍스트 일기, 저녁 마음꽃 |
| 03 | 목표 추천, 1주·2주·한 달 설정, 체크와 실행률 |
| 04 | 주행 화면에서 기본·음성·바람 반응 시연, 정차 화면 복귀 |
| 06–08 | 월별 책, 날짜 페이지, 연도 선택, 일기 상세 및 수정·삭제 |
| 09–10 | 월간 요약, 리캡, 정원, 월별 화단, 꽃도감, 연속 기록 |

날씨와 운전 화면은 수동으로 제어하는 시연입니다. 주행 화면의 경로와 캐릭터 반응은 실제 차량 신호와 연결되지 않습니다.
음성 인식은 Android 12 이상에서 온디바이스 한국어 인식 서비스를 사용할 수 있을 때 작동합니다.
정지 버튼 또는 인식 종료 시 변환된 텍스트를 기기에 저장합니다. 음성 인식을 사용할 수 없으면 텍스트 입력창을 엽니다.
원본 음성 파일은 남기지 않으므로 일기 상세의 음성 기록 탭에서 인식 텍스트를 읽습니다.
감정과 목표 제안은 기기에서 키워드 규칙으로 계산하고, 저녁에 꽃을 선택하면 월간 통계와 도감에 반영합니다.
음악 듣기는 앱에 담은 짧은 시연 음원을 재생합니다. 일기, 씨앗, 목표를 포함한 로컬 데이터는 정보 관리 메뉴에서 삭제할 수 있습니다.

## 검증

GitHub Actions는 디버그 APK 빌드, Android lint, API 34 에뮬레이터의 계측 테스트를 실행합니다.
Actions 실행의 `sai-debug-apk`와 `sai-test-reports` 아티팩트에서 결과를 확인할 수 있습니다.
마이크 입력과 차량 데이터는 해당 기능이 지원되는 Pleos 에뮬레이터 또는 실기기에서 별도 확인해야 합니다.

- [GitHub Actions](https://github.com/BBIYACK-0834/Hyundai_Sai/actions)
- [Figma 프로토타입](https://www.figma.com/design/V6DJBZOInaNymTAp1dgc66/?node-id=392-179)
- [Pleos 개발 시작](https://document.pleos.ai/docs/pleos-only/getting-started)
