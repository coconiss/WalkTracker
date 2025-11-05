# WalkTrackerApp (걸음 수 추적 앱)

이 프로젝트는 사용자의 걸음을 추적하고 친구들과 순위를 비교할 수 있는 네이티브 안드로이드 애플리케이션입니다.

## 🌟 주요 기능

*   **로그인 및 인증**: Firebase Authentication을 사용하여 간편하게 이메일/비밀번호 또는 소셜 로그인을 할 수 있습니다. 이를 통해 사용자의 데이터가 안전하게 클라우드에 저장됩니다.
*   **실시간 활동 추적**: `Foreground Service`를 사용하여 앱이 백그라운드에 있을 때도 걸음 수, 이동 거리, 소모 칼로리, 고도 변화를 안정적으로 측정합니다.
*   **지능형 배터리 관리**: 사용자의 활동(걷기, 뛰기, 정지, 차량 이동)을 자동으로 감지하여 위치 업데이트 빈도를 동적으로 조절하고, 장시간 활동이 없으면 자체 절전 모드로 진입하여 배터리 소모를 최소화합니다.
*   **랭킹 시스템**: 다른 사용자들과의 걸음 수를 비교하여 주간 랭킹을 확인할 수 있습니다. 경쟁을 통해 운동 동기를 부여받을 수 있습니다.
*   **모던 UI**: Jetpack Compose를 사용하여 전체 UI를 구현하였으며, 시스템 설정(다크/라이트 모드)에 자동으로 대응하는 동적 색상 테마가 적용되어 있습니다.

## 🛠️ 기술 스택 및 아키텍처

*   **언어**: 100% Kotlin
*   **UI**: Jetpack Compose를 사용한 선언적 UI
*   **아키텍처**: MVVM (Model-View-ViewModel) 패턴
*   **백그라운드 처리**: Foreground Service, Activity Recognition API
*   **비동기 처리**: Coroutines & Flow
*   **인증 및 데이터베이스**: Firebase Authentication, Cloud Firestore

## ⚙️ 시스템 요구 사양

이 앱의 핵심 기능인 실시간 백그라운드 위치 및 센서 추적은 하드웨어 성능에 영향을 받습니다. 원활한 사용을 위해 아래 사양을 확인해주세요.

| 구분 | 최소 사양 | 권장 사양 | 비고 |
| :--- | :--- | :--- | :--- |
| **OS** | Android 8.0 (Oreo) | Android 10.0 이상 | `minSdkVersion 26` |
| **RAM** | 2 GB | 3 GB 이상 | Foreground Service의 안정적 동작을 위한 핵심 요소 |
| **센서** | GPS, 걸음수 센서, 가속도계 | 기압계(Pressure) 추가 | 걸음수/활동인식 기능에 필수. 기압계는 고도 측정 정확도 향상 |
| **기타**| Google Play 서비스 필수 | - | Firebase 및 위치 서비스 이용에 필요 |

## 🚀 프로젝트 설정 방법

이 프로젝트를 빌드하고 실행하려면, 프로젝트 기능에 필요한 Google 서비스를 설정해야 합니다.

### `google-services.json` 설정

이 프로젝트는 Firebase와 같은 Google 서비스를 인증, 데이터베이스 등의 기능에 사용합니다. 따라서, 자신만의 `google-services.json` 설정 파일이 필요합니다.

1.  [Firebase 콘솔](https://console.firebase.google.com/)로 이동합니다.
2.  새로운 프로젝트를 생성하거나 기존 프로젝트를 선택합니다.
3.  **Firebase 프로젝트에 Android 앱 추가하기**

    Firebase 콘솔의 프로젝트 개요 페이지에서 '앱 추가' 버튼을 누르고 Android 아이콘을 선택하여 설정 과정을 시작합니다.

    *   **1단계: 앱 등록**
        *   **Android 패키지 이름**: `com.walktracker.app` 을 정확하게 입력해야 합니다. (`app/build.gradle.kts`의 `applicationId`에서 확인 가능합니다.)
        *   **앱 닉네임 (선택사항)**: 'WalkTracker'와 같이 프로젝트를 식별할 수 있는 이름을 자유롭게 입력합니다.
        *   **디버그 서명 인증서 SHA-1 (필수)**: Google 로그인 등 특정 Firebase 기능을 사용하려면 **반드시 필요합니다.** 개발용(디버그) SHA-1 인증서는 Android Studio에서 다음과 같이 쉽게 찾을 수 있습니다.
            1.  Android Studio 오른쪽의 **Gradle** 툴 윈도우를 엽니다.
            2.  **WalkTrackerApp > :app > Tasks > android** 로 이동합니다.
            3.  `signingReport`를 더블 클릭하여 실행합니다.
            4.  하단의 **Run** 탭에 빌드 결과가 나타나면, `debug` Variant의 **SHA-1** 키 값을 복사하여 Firebase 콘솔에 붙여넣습니다.

    *   '앱 등록' 버튼을 누른 후 다음 단계로 진행합니다.

4.  **2단계: 구성 파일 다운로드**

    'google-services.json 다운로드' 버튼을 클릭하여 설정 파일을 다운로드합니다.

5.  **3단계: 구성 파일 추가**

    다운로드한 `google-services.json` 파일을 프로젝트의 **`app`** 모듈 루트 디렉터리에 복사합니다. 최종 경로는 `D:/study/android/app/google-services.json` 이어야 합니다.

## 빌드 및 실행

1.  위의 `google-services.json` 설정 단계를 완료합니다.
2.  Android Studio에서 프로젝트를 엽니다.
3.  Gradle 동기화가 완료될 때까지 기다립니다.
4.  실제 기기를 연결하거나 에뮬레이터를 실행한 후, 'Run' 버튼 (▶️)을 클릭하여 앱을 빌드하고 실행합니다.
