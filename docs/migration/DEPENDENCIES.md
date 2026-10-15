# 原版直接依赖（同步基线的清单与 lock）

生成自各模块 `pyproject.toml` 和 `uv.lock`；不把直接依赖清单当作完整传递 SBOM。

## mcp_client

| 清单要求 | lock 版本 |
| --- | --- |
| `asyncpg>=0.31.0` | 0.31.0 |
| `google-adk[all]>=2.4.0` | 2.4.0 |
| `google-cloud-storage>=3.13.0` | 3.13.0 |
| `json-repair>=0.61.5` | 0.61.5 |
| `pillow>=12.3.0` | 12.3.0 |
| `python-dotenv>=1.2.2` | 1.2.2 |

## mcp_montage

| 清单要求 | lock 版本 |
| --- | --- |
| `audioop-lts>=0.2.2` | 0.2.2 |
| `google-cloud-storage>=3.13.0` | 3.13.0 |
| `google-genai>=2.12.0` | 2.12.0 |
| `httpx>=0.28.1` | 0.28.1 |
| `mcp>=1.28.1` | 1.28.1 |
| `numpy>=2.5.1` | 2.5.1 |
| `pillow>=12.3.0` | 12.3.0 |
| `pydub>=0.25.1` | 0.25.1 |
| `pyopenssl<26.2.0` | 26.1.0 |
| `python-dotenv>=1.2.2` | 1.2.2 |
| `torch>=2.11,<2.13` | 2.13.0 |
| `torchaudio>=2.11.0` | 2.11.0 |
| `torchcodec>=0.15.0` | 0.15.0 |
| `uvicorn[standard]>=0.51.0` | 0.51.0 |

## sign_server

| 清单要求 | lock 版本 |
| --- | --- |
| `fastapi>=0.139.1` | 0.139.1 |
| `google-cloud-storage>=3.13.0` | 3.13.0 |
| `python-dotenv>=1.2.2` | 1.2.2 |
| `uvicorn[standard]>=0.51.0` | 0.51.0 |
