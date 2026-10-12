"""로그인 쿠키 없이 인스타그램 공개 프로필 응답의 구조만 확인한다."""

import json
import http.cookiejar
import re
import ssl
import urllib.error
import urllib.request
from pathlib import Path


def main():
    """응답 상태와 공개 데이터 키를 기록하며 쿠키 저장소는 읽지 않는다."""
    cert = "/data/user/0/com.junkfood.seal.debug/no_backup/youtubedl-android/packages/python/usr/etc/tls/cert.pem"
    context = ssl.create_default_context(cafile=cert)
    # 이번 공개 탐색에서 서버가 발급한 값만 유지하고 앱의 등록 쿠키는 읽지 않는다.
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context), urllib.request.HTTPCookieProcessor(jar))
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "X-IG-App-ID": "936619743392459",
        "Referer": "https://www.instagram.com/dianyang0928/",
        "Accept": "*/*",
    }
    for name, url in (
        ("page", "https://www.instagram.com/dianyang0928/"),
        ("profile", "https://www.instagram.com/api/v1/users/web_profile_info/?username=dianyang0928"),
    ):
        try:
            with opener.open(urllib.request.Request(url, headers=dict(headers, **({"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language": "en-US,en;q=0.9", "Sec-Fetch-Dest": "document", "Sec-Fetch-Mode": "navigate", "Sec-Fetch-Site": "none", "Sec-Fetch-User": "?1", "Upgrade-Insecure-Requests": "1"} if name == "page" else {}))), timeout=30) as response:
                body = response.read().decode("utf-8")
                Path(f"cache/public-profile-{name}.txt").write_text(body)
                result = {"name": name, "status": response.status, "path": response.url.split("instagram.com")[-1], "bytes": len(body)}
                try:
                    data = json.loads(body)
                    user = data.get("data", {}).get("user", {})
                    result.update(keys=list(data), user_keys=list(user), count=len(user.get("edge_owner_to_timeline_media", {}).get("edges", [])))
                except ValueError:
                    result["json"] = False
                print(json.dumps(result), flush=True)
        except urllib.error.HTTPError as error:
            print(json.dumps({"name": name, "status": error.code}), flush=True)
    html = Path("cache/public-profile-page.txt").read_text()
    objects = []

    def visit(value):
        """문서에 공개된 JSON 객체만 재귀적으로 수집한다."""
        if isinstance(value, dict):
            objects.append(value)
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    for block in re.findall(r"<script\b[^>]*>(\{.*?\})</script>", html, re.S | re.I):
        try:
            visit(json.loads(block))
        except ValueError:
            pass
    query = next((item for item in objects if item.get("queryName") == "PolarisLoggedOutDesktopWWWProfilePostsTabContentQuery"), None)
    connection = next((item["polaris_ordered_timeline_connection"] for item in objects if "polaris_ordered_timeline_connection" in item), None)
    if query and connection:
        lsd = re.search(r'\["LSD",\[\],\{"token":"([^"]+)"', html).group(1)
        variables = dict(query["variables"], after=connection["page_info"]["end_cursor"])
        data = urllib.parse.urlencode({"lsd": lsd, "__a": "1", "__user": "0", "__comet_req": "7", "jazoest": "2"+str(sum(map(ord,lsd))), "fb_api_caller_class": "RelayModern", "fb_api_req_friendly_name": query["queryName"], "doc_id": query["queryID"], "variables": json.dumps(variables)}).encode()
        next_headers = dict(headers, **{"X-FB-LSD": lsd, "X-FB-Friendly-Name": query["queryName"]})
        csrf = next((cookie.value for cookie in jar if cookie.name == "csrftoken"), None)
        if csrf:
            next_headers["X-CSRFToken"] = csrf
        request = urllib.request.Request("https://www.instagram.com/graphql/query/", data=data, headers=next_headers)
        try:
            with opener.open(request, timeout=30) as response:
                body = response.read().decode()
                Path("cache/public-profile-next.txt").write_text(body)
                print(json.dumps({"name":"next-format", "path":response.url.split("instagram.com")[-1], "prefix":body[:50]}),flush=True)
                result = json.loads(body.removeprefix("for (;;);"))
                print(json.dumps({"name": "next", "status": response.status, "keys": list(result), "data_keys": list(result.get("data") or {}), "errors": result.get("errors")}), flush=True)
        except urllib.error.HTTPError as error:
            print(json.dumps({"name": "next", "status": error.code}), flush=True)


if __name__ == "__main__":
    main()
