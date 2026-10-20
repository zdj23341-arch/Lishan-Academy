"""Apply the approved launch defaults through the authenticated admin API.

The script is intentionally separate from order logic. It reads every rule before writing,
uses the returned optimistic-lock version, and leaves an existing article untouched when the
same title already exists. It never handles payment, withdrawal, or customer credentials.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


SUPPORTED_MAINLAND_REGIONS = (
    "110000", "120000", "130000", "140000", "210000", "220000", "230000",
    "310000", "320000", "330000", "340000", "350000", "360000", "370000",
    "410000", "420000", "430000", "440000", "450000", "460000", "500000",
    "510000", "520000", "530000", "610000",
)

BLOCKED_REGIONS = {
    "150000": "内蒙古",
    "540000": "西藏",
    "620000": "甘肃",
    "630000": "青海",
    "640000": "宁夏",
    "650000": "新疆",
}

UNSUPPORTED_REGIONS = {
    "710000": "台湾",
    "810000": "香港",
    "820000": "澳门",
}

FREE_SHIPPING = {"enabled": True, "freightFen": 0, "freeThresholdFen": None}
DISABLED_SHIPPING = {"enabled": False, "freightFen": 0, "freeThresholdFen": None}
REGION_POLICIES = {
    **{code: dict(FREE_SHIPPING) for code in SUPPORTED_MAINLAND_REGIONS},
    **{code: dict(DISABLED_SHIPPING) for code in BLOCKED_REGIONS},
    **{code: dict(DISABLED_SHIPPING) for code in UNSUPPORTED_REGIONS},
}

SHIPPING_REASON = (
    "上线配送政策：除新疆、西藏、青海、内蒙古、宁夏、甘肃外的中国大陆省级地区包邮；"
    "港澳台暂不配送。"
)

AGREEMENT_ARTICLES = (
    {
        "kind": "terms",
        "title": "力山学堂用户协议",
        "version": "1.0",
        "authorName": "力山学堂",
        "categoryName": "服务协议",
        "status": "PUBLISHED",
        "contentBlocks": [
            {
                "type": "TEXT",
                "text": """力山学堂用户协议
版本：1.0

一、协议范围
欢迎使用力山学堂小程序。本协议说明你使用课程、图书、会员、试卷、订单、学习和分销相关功能时与运营方之间的权利义务。你勾选同意并继续使用，即表示已阅读并接受本协议和《力山学堂隐私政策》。

二、账号与使用
你应通过本人微信账号使用服务，妥善保管登录设备，不得出借、转让账号或利用服务从事违法活动。因你自身原因造成的账号使用风险由你承担。

三、商品、内容与订单
课程、会员、试卷和图书的名称、价格、库存、有效期、配送范围及其他交易条件以提交订单前页面展示为准。图书订单按收货地址核算配送资格；不支持配送的地区无法提交订单。订单创建、支付、退款和售后按页面规则及适用法律处理。

四、内容使用
课程、试卷、图文和其他资料仅限你在授权范围内学习或使用。未经许可，不得复制、传播、出租、出售、抓取或用于商业用途。你发布或提交的内容不得侵犯他人合法权益。

五、分销与收益
分销资格、商品推广、佣金、提现资格和到账状态以服务端实际记录及微信相关渠道结果为准。处理中或待确认状态不代表已经到账，不得通过伪造请求、重复操作或其他方式获取不当收益。

六、服务变更
我们会根据业务、法律法规和安全需要更新服务。涉及用户重要权益的变更会通过小程序页面或其他适当方式提示。因网络、第三方平台或不可抗力导致的暂时中断，我们会在可行范围内恢复服务。

七、知识产权与责任
小程序及其内容中的商标、软件、文字、图片和其他资料受法律保护。除法律另有规定外，未经授权不得使用。你违反本协议造成损失的，应依法承担相应责任。

八、联系我们
如需咨询、投诉、售后或行使与账号相关的权利，请通过小程序内客服入口联系运营方。

九、协议更新与生效
我们会在本页面发布更新后的协议并标注版本。协议更新后，继续使用相关服务表示接受更新内容；如你不同意，应停止使用受影响的服务。""",
            }
        ],
    },
    {
        "kind": "privacy",
        "title": "力山学堂隐私政策",
        "version": "1.0",
        "authorName": "力山学堂",
        "categoryName": "服务协议",
        "status": "PUBLISHED",
        "contentBlocks": [
            {
                "type": "TEXT",
                "text": """力山学堂隐私政策
版本：1.0

本政策说明力山学堂小程序在提供服务时如何处理与保护你的信息。我们只在实现服务、保障安全、履行法律义务所必要的范围内处理信息。

一、我们处理的信息
1. 登录和账号：使用微信登录时，我们会处理完成登录、识别账号和维持会话所必需的信息。
2. 交易和履约：购买图书、课程或会员时，我们会处理商品、订单、支付状态和售后相关信息；图书配送还需要你填写的收货人、联系电话和收货地址。
3. 学习和内容：使用课程、试卷、下载和学习功能时，我们会处理必要的课程权益、观看进度、授权记录和下载审计信息。
4. 分销服务：使用推广、佣金和提现功能时，我们会处理分销资格、归因、佣金和提现状态等必要信息。
5. 安全与运行：为防止欺诈、攻击和重复操作，我们会处理必要的请求标识、操作记录和运行日志。

二、使用目的
我们使用上述信息来完成登录、提供商品和内容、处理订单支付与退款、配送图书、保存学习进度、处理售后和分销服务，并保障系统稳定和账号安全。我们不会将你的个人信息用于与这些目的无关的用途。

三、第三方服务
在微信登录、支付、消息或其他你主动使用的功能中，我们可能向微信或相关服务提供完成该功能所必要的信息。第三方会按照其自身的规则处理信息；我们不会要求你提供与功能无关的信息。

四、保存与保护
我们采取访问控制、加密或脱敏存储、操作审计和其他合理措施保护信息。信息仅在实现业务目的、处理争议、履行法定义务所需期间保存，超过必要期限后删除或匿名化处理；法律另有规定的除外。

五、你的权利
你可以在小程序内查看或修改可编辑的账号、收货地址和相关信息，也可以申请查询、更正、删除个人信息或注销账号。部分信息删除后可能无法继续使用相应服务。请通过小程序内客服入口提出申请，我们会按法律规定处理。

六、未成年人
未成年人应在监护人同意和指导下使用本小程序。监护人如发现未成年人未经同意提供了个人信息，可以通过小程序内客服入口联系我们。

七、政策更新
我们会在本页面发布更新后的政策并标注版本。发生重大变化时，我们会以适当方式提示并在需要时重新征得你的同意。

八、联系我们
如对本政策或个人信息处理有疑问、投诉或权利请求，请通过小程序内客服入口联系运营方。""",
            }
        ],
    },
)


class ApiError(RuntimeError):
    def __init__(self, method: str, path: str, status: int, payload: Any):
        super().__init__(f"{method} {path} failed with HTTP {status}: {payload}")
        self.status = status
        self.payload = payload


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(self, method: str, path: str, token: str | None = None, body: dict | None = None) -> dict:
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(self.base_url + path, method=method, headers=headers, data=data)
        try:
            with urlopen(request, timeout=30) as response:
                status = response.status
                payload = json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            status = error.code
            payload = json.loads(error.read().decode("utf-8"))
        except URLError as error:
            raise RuntimeError(f"Cannot reach {self.base_url}: {error.reason}") from error
        if status < 200 or status >= 300 or payload.get("code") != "OK":
            raise ApiError(method, path, status, payload)
        return payload["data"]

    def login(self, login_name: str, password: str) -> str:
        return self.request(
            "POST", "/api/v1/admin/auth/login", body={"loginName": login_name, "password": password}
        )["accessToken"]


def article_payload(article: dict[str, Any]) -> dict[str, Any]:
    return {
        "version": 0,
        **{key: value for key, value in article.items() if key != "kind" and key != "version"},
    }


def ensure_articles(client: ApiClient, token: str) -> dict[str, dict[str, Any]]:
    articles = client.request("GET", "/api/v1/admin/articles?page=1&pageSize=100", token)["items"]
    result: dict[str, dict[str, Any]] = {}
    for article in AGREEMENT_ARTICLES:
        existing = next((item for item in articles if item["title"] == article["title"]), None)
        if existing is not None:
            result[article["kind"]] = {"id": existing["id"], "created": False, "title": existing["title"]}
            continue
        created = client.request("POST", "/api/v1/admin/articles", token, article_payload(article))
        result[article["kind"]] = {"id": created["id"], "created": True, "title": created["title"]}
    return result


def apply_shipping_policy(client: ApiClient, token: str) -> dict[str, Any]:
    current = client.request("GET", "/api/v1/admin/shipping-rules?page=1&pageSize=100", token)["items"]
    by_code = {item["provinceCode"]: item for item in current}
    changes: list[dict[str, Any]] = []
    for code, desired in REGION_POLICIES.items():
        existing = by_code.get(code)
        current_state = None if existing is None else {
            "enabled": existing["enabled"],
            "freightFen": existing["freightFen"],
            "freeThresholdFen": existing["freeThresholdFen"],
        }
        if current_state == desired:
            changes.append({"provinceCode": code, "action": "unchanged", **desired})
            continue
        body = {
            "version": 0 if existing is None else existing["version"],
            **desired,
            "reason": SHIPPING_REASON,
        }
        saved = client.request("PUT", f"/api/v1/admin/shipping-rules/{code}", token, body)
        changes.append({"provinceCode": code, "action": "updated", **saved})
    return {"beforeCount": len(current), "changes": changes}


def write_agreement_properties(path: Path, article_ids: dict[str, dict[str, Any]]) -> None:
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    lines = text.splitlines()
    values = {
        "jxsc.agreements.terms.article-id": article_ids["terms"]["id"],
        "jxsc.agreements.terms.version": "1.0",
        "jxsc.agreements.privacy.article-id": article_ids["privacy"]["id"],
        "jxsc.agreements.privacy.version": "1.0",
    }
    seen: set[str] = set()
    output: list[str] = []
    for line in lines:
        key = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else ""
        if key in values:
            output.append(f"{key}={values[key]}")
            seen.add(key)
        else:
            output.append(line)
    for key, value in values.items():
        if key not in seen:
            output.append(f"{key}={value}")
    path.write_text("\n".join(output) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("JXSC_ADMIN_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--login", default=os.environ.get("JXSC_ADMIN_LOGIN"))
    parser.add_argument("--password", default=os.environ.get("JXSC_ADMIN_PASSWORD"))
    parser.add_argument("--properties-file", type=Path)
    args = parser.parse_args()
    if not args.login or not args.password:
        parser.error("provide --login/--password or JXSC_ADMIN_LOGIN/JXSC_ADMIN_PASSWORD")
    client = ApiClient(args.base_url)
    token = client.login(args.login, args.password)
    articles = ensure_articles(client, token)
    shipping = apply_shipping_policy(client, token)
    if args.properties_file:
        write_agreement_properties(args.properties_file, articles)
    print(json.dumps({"articles": articles, "shipping": shipping, "propertiesFile": str(args.properties_file) if args.properties_file else None}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
