import firebase_admin
from firebase_admin import credentials, firestore
import datetime
import os # os 모듈 추가

# --- GitHub Actions 환경에서 실행 ---
# GitHub Secrets에서 서비스 계정 정보를 가져와 인증
# 로컬 테스트 시에는 'path/to/key.json'을 직접 사용
cred_json = os.environ.get('FIREBASE_CREDENTIALS_JSON')

if cred_json:
    # GitHub Actions에서 실행될 때 (환경 변수 사용)
    import json
    cred_dict = json.loads(cred_json)
    cred = credentials.Certificate(cred_dict)
else:
    # 로컬에서 테스트할 때 (파일 경로 사용)
    # 아래 파일 경로는 실제 키 파일의 위치로 수정해야 합니다.
    cred = credentials.Certificate("path/to/your/serviceAccountKey.json")

firebase_admin.initialize_app(cred)
db = firestore.client()

def update_ranking_for_period(period_type: str, period_key: str):
    """지정된 기간의 랭킹을 계산하고 업데이트하는 범용 함수"""
    print(f"Starting ranking update for {period_type}/{period_key}...")
    
    # Firestore에서 해당 기간의 데이터를 'steps' 기준으로 내림차순 정렬하여 가져옴
    rankings_ref = db.collection(f"rankings/{period_type}/{period_key}")
    docs_snapshot = rankings_ref.order_by("steps", direction=firestore.Query.DESCENDING).stream()

    # Firestore의 Batch Write 기능을 사용하여 여러 문서를 한 번에 효율적으로 업데이트
    batch = db.batch()
    rank = 1
    doc_count = 0
    
    for doc in docs_snapshot:
        doc_count += 1
        batch.update(doc.reference, {"rank": rank})
        rank += 1
        
        # Batch는 500개 쓰기 제한이 있으므로, 500개마다 커밋
        if doc_count % 500 == 0:
            batch.commit()
            batch = db.batch() # 새 배치 시작

    if doc_count > 0:
        batch.commit() # 남은 배치 커밋
        print(f"Successfully updated {doc_count} user ranks for {period_type}/{period_key}.")
    else:
        print(f"No documents found for {period_type}/{period_key}. Nothing to update.")

def main():
    """실행할 랭킹 업데이트 작업을 정의"""
    
    # 1. 어제의 일간 랭킹 집계
    # 한국 시간 기준으로 어제를 계산 (UTC 기준으로는 다를 수 있음)
    # GitHub Actions는 UTC 기준이므로, 한국 시간 오전 9시에 실행하면 UTC 0시가 되어 날짜 계산이 쉬움
    yesterday = datetime.date.today() - datetime.timedelta(days=1)
    daily_key = yesterday.strftime("%Y-%m-%d")
    update_ranking_for_period("daily", daily_key)

    # 2. 지난달의 월간 랭킹 집계 (매월 1일에만 실행되도록)
    today = datetime.date.today()
    if today.day == 1:
        last_month_date = today.replace(day=1) - datetime.timedelta(days=1)
        monthly_key = last_month_date.strftime("%Y-%m")
        update_ranking_for_period("monthly", monthly_key)

    # 3. 작년의 연간 랭킹 집계 (매년 1월 1일에만 실행되도록)
    if today.month == 1 and today.day == 1:
        last_year = today.year - 1
        yearly_key = str(last_year)
        update_ranking_for_period("yearly", yearly_key)

if __name__ == "__main__":
    main()
