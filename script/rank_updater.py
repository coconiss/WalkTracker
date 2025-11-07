import firebase_admin
from firebase_admin import credentials, firestore
import datetime
import os
import calendar

# --- Firebase 초기화 ---
cred_json = os.environ.get('FIREBASE_CREDENTIALS_JSON')
if cred_json:
    import json
    cred_dict = json.loads(cred_json)
    cred = credentials.Certificate(cred_dict)
else:
    # 로컬 테스트 시 실제 키 파일 경로로 수정하세요.
    cred = credentials.Certificate("path/to/your/serviceAccountKey.json")

firebase_admin.initialize_app(cred)
db = firestore.client()

# --- 사용자 정보 캐싱을 위한 헬퍼 ---
user_cache = {}
def get_user_display_name(user_id):
    """사용자 ID로 displayName을 조회하고 캐시에 저장합니다."""
    if user_id in user_cache:
        return user_cache[user_id]

    user_ref = db.collection('users').document(user_id)
    user_doc = user_ref.get()
    if user_doc.exists:
        display_name = user_doc.to_dict().get('displayName', 'Unknown User')
        user_cache[user_id] = display_name
        return display_name
    return 'Unknown User'

def update_ranking_for_period(period_type: str, period_key: str):
    """daily_activities를 기반으로 지정된 기간의 랭킹을 계산하고 업데이트합니다."""
    print(f"Starting ranking calculation for {period_type}/{period_key}...")

    # 1. 기간에 따른 날짜 범위 설정
    if period_type == 'daily':
        start_date = end_date = period_key
    elif period_type == 'monthly':  # period_key: "YYYY-MM"
        year, month = map(int, period_key.split('-'))
        start_date = f"{period_key}-01"
        last_day = calendar.monthrange(year, month)[1]
        end_date = f"{period_key}-{last_day:02d}"
    elif period_type == 'yearly':  # period_key: "YYYY"
        start_date = f"{period_key}-01-01"
        end_date = f"{period_key}-12-31"
    else:
        print(f"Unknown period type: {period_type}")
        return

    # 2. daily_activities 컬렉션에서 데이터 조회
    activities_ref = db.collection('daily_activities')
    docs_snapshot = activities_ref.where('date', '>=', start_date).where('date', '<=', end_date).stream()

    # 3. 사용자별로 거리 합산
    user_distances = {}  # { userId: totalDistance }
    for doc in docs_snapshot:
        activity = doc.to_dict()
        user_id = activity.get('userId')
        distance = activity.get('distance', 0)
        if user_id:
            user_distances[user_id] = user_distances.get(user_id, 0) + distance

    if not user_distances:
        print(f"No activities found for {period_type}/{period_key}. Nothing to update.")
        return

    # 4. 총 거리를 기준으로 랭킹 정렬
    sorted_rankings = sorted(user_distances.items(), key=lambda item: item[1], reverse=True)

    # 5. Firestore에 배치 쓰기 준비
    batch = db.batch()
    for rank, (user_id, total_distance) in enumerate(sorted_rankings, 1):
        display_name = get_user_display_name(user_id)

        # rankings 컬렉션에 저장할 문서 ID 및 데이터 생성
        doc_id = f"{period_type}_{period_key}_{user_id}"
        ranking_ref = db.collection('rankings').document(doc_id)

        data = {
            "userId": user_id,
            "displayName": display_name,
            "distance": total_distance,
            "period": period_type,
            "periodKey": period_key,
            "rank": rank, # 계산된 순위
            "updatedAt": firestore.SERVER_TIMESTAMP
        }
        # set(merge=True) 대신 set()을 사용하여 이전 집계 데이터를 완전히 덮어씁니다.
        batch.set(ranking_ref, data)

        if rank % 500 == 0:
            batch.commit()
            batch = db.batch()

    batch.commit()
    print(f"Successfully calculated and updated {len(sorted_rankings)} user ranks for {period_type}/{period_key}.")


def main():
    """실행할 랭킹 업데이트 작업을 정의합니다."""
    # GitHub Actions는 UTC 기준이므로, 한국 시간 오전 9시(UTC 0시) 실행을 권장합니다.
    # 스크립트는 실행 시점의 UTC 날짜를 기준으로 계산합니다.

    # 1. 어제의 일간 랭킹 집계
    yesterday = datetime.date.today() - datetime.timedelta(days=1)
    daily_key = yesterday.strftime("%Y-%m-%d")
    update_ranking_for_period("daily", daily_key)

    # 2. 지난달의 월간 랭킹 집계 (매월 1일에만 실행)
    today = datetime.date.today()
    if today.day == 1:
        last_month_date = today.replace(day=1) - datetime.timedelta(days=1)
        monthly_key = last_month_date.strftime("%Y-%m")
        update_ranking_for_period("monthly", monthly_key)

    # 3. 작년의 연간 랭킹 집계 (매년 1월 1일에만 실행)
    if today.month == 1 and today.day == 1:
        last_year = today.year - 1
        yearly_key = str(last_year)
        update_ranking_for_period("yearly", yearly_key)

if __name__ == "__main__":
    main()
