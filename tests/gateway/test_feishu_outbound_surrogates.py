"""Regression coverage for Feishu outbound lone-surrogate handling (#113799)."""

from __future__ import annotations

import asyncio
import json
from unittest.mock import AsyncMock

from tests.gateway._plugin_adapter_loader import load_plugin_adapter

_adapter = load_plugin_adapter("feishu")


def _bare_adapter():
    return object.__new__(_adapter.FeishuAdapter)


def test_outbound_text_payload_replaces_lone_surrogates_before_json_encoding():
    msg_type, payload = _bare_adapter()._build_outbound_payload("hello\ud800world")

    assert msg_type == "text"
    assert json.loads(payload) == {"text": "hello\ufffdworld"}
    assert "\ud800" not in payload


def test_outbound_markdown_post_replaces_lone_surrogates_before_json_encoding():
    msg_type, payload = _bare_adapter()._build_outbound_payload("**hello \udfffworld**")

    assert msg_type == "post"
    assert "\udfff" not in payload
    assert json.loads(payload)["zh_cn"]["content"][0][0]["text"] == "**hello \ufffdworld**"


def test_captioned_reply_media_payload_replaces_lone_surrogates():
    adapter = _bare_adapter()
    adapter._feishu_send_with_retry = AsyncMock(return_value=object())

    asyncio.run(adapter._send_uploaded_key(
        chat_id="oc_chat",
        reply_to="om_parent",
        metadata={"thread_id": "omt_thread"},
        caption="caption \ud800text",
        key_msg_type="image",
        key_payload={"image_key": "img_key"},
        media_tag={"tag": "img", "image_key": "img_key"},
    ))

    sent = adapter._feishu_send_with_retry.await_args.kwargs
    assert sent["msg_type"] == "post"
    assert sent["reply_to"] == "om_parent"
    assert "\ud800" not in sent["payload"]
    assert json.loads(sent["payload"])["zh_cn"]["content"][0][0]["text"] == "caption \ufffdtext"
