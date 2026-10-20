# 뀨 리더 (Q Reader) Android v0.3 Alpha

Android Studio / GitHub Actions용 **소스 프로젝트**입니다. APK가 아닙니다. 아직 Android SDK를 통한 실제 Gradle 컴파일이나 삼성 실기기 테스트는 완료되지 않았습니다.

## 중요 기능
- 안드로이드 시스템 파일 선택기에서 TXT / ZIP / JPG / PNG / WEBP 열기
- 본문 검색, 책갈피, 빠른 탐색, 연속 자동 스크롤, 화면 밝기 조절
- 글꼴: 시스템 기본 / 명조 / 고딕 / 고정폭 / 개인 TTF 또는 OTF 가져오기
- 글자 크기 12~54sp, 줄 간격, 좌우 여백, 세 가지 배경 테마
- 기본 TTS, 언어별 문자 생략과 발음 치환, 취침 타이머(문장 경계 종료 및 단계적 구간 음량 축소)
- 이미지 한국어 OCR, ZIP에 포함된 텍스트·이미지 OCR, 외부 검색 링크

## 이번 수정
- 미리보기의 잘못된 `text.typeface` 참조 수정
- 기본 글꼴 중복 선택지 정리, 사용자 글꼴 불러오기
- TTS 대용량 파일 읽기 및 문장 분석을 백그라운드 워커로 이동
- 대기 시 잦은 화면 갱신을 300ms로 완화, 자동 스크롤은 시간 차 기반
- 포그라운드 서비스 mediaPlayback / SAF 지속 권한 기존 구현 유지
- 파일 인코딩을 파일 URI 기준으로 저장, CP949 대체 경로
- 화면 꺼짐 재생 관련 서비스 취소 작업의 늦은 응답 무시
- Auto Backup 활성화(계정/기기 설정에 따라 동작하며 완전한 복원 보장 아님)
- 이미지 긴 캡처는 일부 타일 분할 시도(초장형 비트맵 메모리 위험은 남음)
- OCR 미사용 중국어/일본어 모델 의존성 제거

## APK 빌드
1. ZIP을 풀어 GitHub 리포지토리에 업로드합니다.
2. Actions의 `Android debug APK` 워크플로를 실행합니다.
3. 개인 장기 사용 APK는 서명키를 따로 생성하고 Actions Secrets를 설정한 후 **signed-release**를 사용합니다.
4. 동일 앱을 업데이트하려면 서명키와 applicationId를 유지하고 versionCode를 올려야 합니다.

GitHub Secrets: `QREADER_KEYSTORE_BASE64`, `QREADER_STORE_PASSWORD`, `QREADER_KEY_ALIAS`, `QREADER_KEY_PASSWORD`.
서명키와 암호를 공개 저장소나 ZIP에 포함하지 마세요.

## 아직 미완료 / 검수 필요
- 실제 컴파일 및 APK 설치 시험, 화면 꺼짐 장시간 TTS, 미디어 세션/블루투스 리모컨
- 전체 TXT를 단일 TextView에 올리는 문제: 대용량에서 ANR 가능
- 본문 검색 200건 목록 상한, 검색 시 전체 Spannable 재배치 비용
- ZIP 전체 메모리 적재와 이미지 OCR 전체 선처리 / OCR 수정본 보존
- TTS 문장 내부 정밀 재개, 백그라운드 재생 유지 성능, 서비스 실패 화면 상태
- 취침 타이머는 합성 구간별 음량 조절이라 재생 중인 문장 안에서 연속 페이드가 되지는 않음
- 수동 JSON 백업/복원, 이미지 목록 정렬/편집, 공식 작품 평점 통합
- 긴 캡처의 메모리 사용 최적화, ZIP 내부 한글 파일명 테스트

원본 소설의 배포/공유는 하지 마세요. 소설 제목 검색만 외부 브라우저로 열며 앱은 별도 서버/API 키 없이 동작합니다.


## v0.4-alpha review fixes
- Sleep timer resets on expiry; deadline persisted to preferences for service recreation.
- TTS reuses previously segmented content in the active session instead of re-reading it on each resume.
- TTS cache is staged before publishing; unchanged existing copies are checked by byte size (not a cryptographic integrity guarantee).
- Android 12+ data extraction rules added while keeping older backup rules.
- Imported custom fonts are validated before replacing a previously working font.
- Existing Android manifest already declares mediaPlayback service type and foreground-service permission.
- Existing document picker attempts persistable URI grants; shared URI grants are not universally persistent.

Unverified: Android Gradle compile, real-device background playback and OEM battery behavior. Not yet implemented: scalable chapter-based rendering, full MediaSession, OCR edit persistence and robust partial-image OCR deduplication.
