(ns rest-api-integration-module.protocol)

(defprotocol RestApiIntegrationModule
  (fetch! [this url]
    "Request an HTTP GET and return nil without waiting for the response.
     After wait-for-processing!, get-body returns the completed response body.
     A completed non-2xx response is stored just like a 2xx response. A later
     completed request for the same URL replaces the earlier body. Transport
     failures are distinct from HTTP status responses; no retry bound is
     promised by this protocol.")
  (get-body [this url]
    "Return the body of the most recently completed response for url, or nil
     when there is no stored response."))
