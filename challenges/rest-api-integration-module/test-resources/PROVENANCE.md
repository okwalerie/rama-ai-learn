# Source provenance

Upstream checkout: `/home/user/workspace/repos/rama-demo-gallery`, HEAD `7b988d986af132a12e5281107e7c0c69287ed704`.

| Retained upstream path | SHA-256 | Retained here | Adaptation |
|---|---|---|---|
| `src/main/clj/rama/gallery/rest_api_integration_module.clj` | `b14bc051766a03a8fc5a9624ada72172c0de717b9b1313f01868f23b529b9b4d` | `rama/gallery/rest_api_integration_module.clj` | Source namespace, AsyncHttpClient task-global lifecycle, Nippy registration, GET future, body extraction, stream and latest-body PState preserved. Comments shortened; unused imports removed. |
| `src/test/clj/rama/gallery/rest_api_integration_module_test.clj` | `5e2ebd4ba2f9ed626a38dfff8ef99d5567d688258d5d751988927f28bde29af4` | Not copied as executable test | External joke API example intentionally excluded; replaced with private disposable loopback fixture. |

`rest_api_integration_module/module.clj` is the protocol adapter. The source does not inspect status codes or configure timeouts, response limits, or a retry cap. The private test verifies that a completed 503 body is stored and avoids deliberately inducing a transport failure, which could cause unbounded source retries. A transport exception remains distinct: `completable-future>` fails topology processing, so the source retries the depot record. This package makes no claim about how quickly that retry occurs or when it stops.

`deps.edn` explicitly includes AsyncHttpClient 2.12.3 because the challenge's Rama dependency bundle does not provide `org.asynchttpclient.AsyncHttpClient` on the isolated harness classpath.
