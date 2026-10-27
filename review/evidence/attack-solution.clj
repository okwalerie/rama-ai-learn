;; IMPORTANT: Before modifying this file, re-read PLAN.md and check pending todos.
;; Adhere to all previously decided design decisions.

(ns attack.solution
  (:require
    [com.rpl.rama :refer :all]
    [com.rpl.rama.path :refer :all]))

;; Answer = ceil(A / B) for 1 <= A, B <= 10^18.
;;
;; Computed as (quot (+ A (dec B)) B): exact 64-bit integer arithmetic, no
;; conditional branch. Largest intermediate is A + B - 1 <= 2*10^18 - 1, well
;; under Long/MAX_VALUE (~9.22*10^18), so there is no overflow. Floating point
;; is unusable here — 10^18 exceeds double's 53-bit exact integer range.
;;
;; Per the challenge Constraint, all computation is dataflow: every operation
;; position below is an existing clojure.core function; no defn/fn/#() helper
;; is defined anywhere in this namespace.
(deframafn solve
  [*input]
  ;; Pull the two digit runs out of the input. This is whitespace-agnostic:
  ;; leading/trailing whitespace, tabs, and "\n"/"\r\n" terminators are all
  ;; ignored without a separate trim or split step.
  (re-seq #"\d+" *input :> *tokens)
  (first *tokens :> *a-str)
  (second *tokens :> *b-str)
  (parse-long *a-str :> *a)
  (parse-long *b-str :> *b)
  (quot (+ *a (dec *b)) *b :> *ans)
  ;; str of a Long is always plain decimal digits — never scientific notation.
  (str *ans :> *out)
  (:> *out))
