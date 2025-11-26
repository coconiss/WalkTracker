import firebase_admin
from firebase_admin import credentials, firestore
import datetime
import os
import calendar
import pytz
import json
import sys

# --- Firebase 초기화 ---
cred_json = os.environ.get('FIREBASE_CREDENTIALS_JSON')

if not cred_json:
    print("[ERROR] 환경 변수 FIREBASE_CREDENTIALS_JSON이 설정되어 있지 않습니다.")
    sys.exit(1)

try:
    cred_dict = json.loads(cred_json)
    cred = credentials.Certificate(cred_dict)
except Exception as e:
    print("[ERROR] Firebase 인증 JSON 파싱 실패:", str(e))
    sys.exit(1)

firebase_admin.initialize_app(cred)
db = firestore.client()

# --- 사용자 정보 캐싱 ---
user_cache = {}

def get_user_display_name(user_id):
    """사용자 ID로 displayName을 조회하고 캐시에 저장."""
    if user_id in user_cache:
        return user_cache[user_id]

    try:
        user_ref = db.collection('users').document(user_id)
        user_doc = user_ref.get()
        if user_doc.exists:
            display_name = user_doc.to_dict().get('displayName', 'Unknown User')
            user_cache[user_id] = display_name
            return display_name
    except Exception as e:
        print(f"[WARN] Failed to fetch user {user_id}: {e}")
    return 'Unknown User'


def aggregate_and_update_ranking(period_type: str, period_key: str):
    """
    daily_activities를 기반으로 지정된 기간의 랭킹을 집계하여
    하나의 문서로 저장합니다.
    """
    print(f"[INFO] Ranking aggregation started: {period_type}/{period_key}")

    # 1. 기간 날짜 설정
    if period_type == 'daily':
        start_date = end_date = period_key
    elif period_type == 'monthly':  # YYYY-MM
        year, month = map(int, period_key.split('-'))
        start_date = f"{period_key}-01"
        last_day = calendar.monthrange(year, month)[1]
        end_date = f"{period_key}-{last_day:02d}"
    elif period_type == 'yearly':  # YYYY
        start_date = f"{period_key}-01-01"
        end_date = f"{period_key}-12-31"
    else:
        print(f"[WARN] Unknown period type: {period_type}")
        return

    # 2. 데이터 조회
    activities_ref = db.collection('daily_activities')
    docs_snapshot = activities_ref.where('date', '>=', start_date) \
                                  .where('date', '<=', end_date) \
                                  .stream()

    # 3. 사용자별 거리 합산
    user_distances = {}
    for doc in docs_snapshot:
        data = doc.to_dict()
        user_id = data.get('userId')
        distance = data.get('distance', 0)
        if user_id:
            user_distances[user_id] = user_distances.get(user_id, 0) + distance

    if not user_distances:
        print(f"[INFO] No activity data found for {period_type}/{period_key}")
        # 데이터가 없어도 문서를 생성하여 클라이언트가 404 에러를 받지 않도록 함
        leaderboard = []
    else:
        # 4. 거리 기준 정렬 및 상위 100명 필터링
        sorted_users = sorted(user_distances.items(), key=lambda x: x[1], reverse=True)
        leaderboard = []
        for rank, (user_id, total_distance) in enumerate(sorted_users[:100], 1):
            display_name = get_user_display_name(user_id)
            leaderboard.append({
                "rank": rank,
                "userId": user_id,
                "displayName": display_name,
                "distance": total_distance
            })

    # 5. Firestore에 단일 랭킹 문서로 저장
    doc_id = f"{period_type}_{period_key}"
    ranking_doc_ref = db.collection('rankings').document(doc_id)

    payload = {
        "leaderboard": leaderboard,
        "updatedAt": firestore.SERVER_TIMESTAMP,
        "period": period_type,
        "periodKey": period_key,
        "totalParticipants": len(user_distances)
    }

    ranking_doc_ref.set(payload)
    print(f"[INFO] Ranking aggregation completed for {doc_id}: {len(leaderboard)} users in leaderboard.")


def main():
    """KST 기준 집계 작업을 수행."""
    kst = pytz.timezone('Asia/Seoul')
    now_kst = datetime.datetime.now(kst)

    # --- 실시간 집계 (현재 기준) ---
    # 1. 오늘의 일간 랭킹
    today_key = now_kst.strftime("%Y-%m-%d")
    aggregate_and_update_ranking("daily", today_key)

    # 2. 이번 달의 월간 랭킹
    monthly_key = now_kst.strftime("%Y-%m")
    aggregate_and_update_ranking("monthly", monthly_key)

    # 3. 올해의 연간 랭킹
    yearly_key = str(now_kst.year)
    aggregate_and_update_ranking("yearly", yearly_key)

    # --- 최종 집계 (어제 기준) ---
    # Github Action이 하루 한 번 00시 직후에 실행될 때,
    # 어제 날짜의 최종본을 한 번 더 확실하게 업데이트합니다.
    yesterday_key = (now_kst - datetime.timedelta(days=1)).strftime("%Y-%m-%d")
    aggregate_and_update_ranking("daily", yesterday_key)


if __name__ == "__main__":
    main()
