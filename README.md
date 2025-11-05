# WalkTrackerApp (걸음 수 추적 앱)

이 프로젝트는 사용자의 걸음을 추적하고 친구들과 순위를 비교할 수 있는 네이티브 안드로이드 애플리케이션입니다.

## 🌟 주요 기능

*   **로그인 및 인증**: Firebase Authentication을 사용하여 간편하게 이메일/비밀번호 또는 소셜 로그인을 할 수 있습니다. 이를 통해 사용자의 데이터가 안전하게 클라우드에 저장됩니다.
*   **걸음 수 추적**: 스마트폰의 센서를 이용하여 사용자의 걸음 수를 실시간으로 추적하고 일별, 주별 통계를 제공합니다. (현재 개발 중인 기능)
*   **랭킹 시스템**: 다른 사용자들과의 걸음 수를 비교하여 주간 랭킹을 확인할 수 있습니다. 경쟁을 통해 운동 동기를 부여받을 수 있습니다.
*   **모던 UI**: Jetpack Compose를 사용하여 전체 UI를 구현하였으며, 시스템 설정(다크/라이트 모드)에 자동으로 대응하는 동적 색상 테마가 적용되어 있습니다.

## 🛠️ 기술 스택 및 아키텍처

*   **언어**: 100% Kotlin
*   **UI**: Jetpack Compose를 사용한 선언적 UI
*   **아키텍처**: MVVM (Model-View-ViewModel) 패턴
*   **비동기 처리**: Coroutines & Flow
*   **인증 및 데이터베이스**: Firebase Authentication, Cloud Firestore
*   **의존성 주입**: Hilt

## 🚀 프로젝트 설정 방법

이 프로젝트를 빌드하고 실행하려면, 프로젝트 기능에 필요한 Google 서비스를 설정해야 합니다.

### `google-services.json` 설정

이 프로젝트는 Firebase와 같은 Google 서비스를 인증, 데이터베이스 등의 기능에 사용합니다. 따라서, 자신만의 `google-services.json` 설정 파일이 필요합니다.

1.  [Firebase 콘솔](https://console.firebase.google.com/)로 이동합니다.
2.  새로운 프로젝트를 생성하거나 기존 프로젝트를 선택합니다.
3.  Firebase 프로젝트에 새 Android 앱을 추가합니다.
    *   패키지 이름은 반드시 `com.walktracker.app` 으로 설정해야 합니다.
    *   디버그 및 릴리스 빌드에 필요한 SHA-1 키를 제공해야 합니다.
4.  생성된 `google-services.json` 파일을 다운로드합니다.
5.  다운로드한 파일을 **`app`** 모듈의 루트 디렉터리에 위치시킵니다. 최종 경로는 `/app/google-services.json` 이어야 합니다.

## 설정 및 실행

1.  위의 `google-services.json` 설정 단계를 완료합니다.
2.  Android Studio에서 프로젝트를 엽니다.
3.  Gradle 동기화가 완료될 때까지 기다립니다.
4.  실제 기기를 연결하거나 에뮬레이터를 실행한 후, 'Run' 버튼 (▶️)을 클릭하여 앱을 빌드하고 실행합니다.
