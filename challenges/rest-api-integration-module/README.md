# REST API integration module

Build a Rama module that fetches URLs with HTTP GET and stores the latest
completed response body for each URL. `fetch!` returns `nil` without waiting
for the response. Call `wait-for-processing!` before reading with `get-body`.
This returns the latest stored body for that URL, or `nil` if none exists.

Close HTTP resources when their owning client or module lifecycle ends.
Choose the resource-lifecycle strategy and Rama topology. Use the supplied
local test fixture rather than the public internet.

Store completed response bodies regardless of HTTP status, including
non-2xx responses. Transport exceptions instead fail asynchronous processing
and cause a retry.

The contract sets no timeout, retry limit, response-size limit or concurrency
bound. Keep tests bounded and use disposable loopback HTTP servers.

Implement `rest-api-integration-module.protocol/RestApiIntegrationModule` and `create-module` returning `{:module ... :wrap-client ...}`. Writes return nil and the client implements `rama-challenges.harness/Synchronizable`.

Write `implementations/rest-api-integration-module/src/rest_api_integration_module/module.clj`. Run `clojure -X:test` and `clojure -X:test-private-harness` from this directory.
