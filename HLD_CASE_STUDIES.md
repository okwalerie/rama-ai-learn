# HLD case-study challenges

These 15 challenges adapt backend correctness problems from [The HLD Handbook's case studies](https://hld.handbook.academy/curriculum/case-studies/). Each challenge's README and protocol define its complete, bounded contract; the source articles supply context, not additional hidden requirements. They are not claims to reproduce the named companies' complete infrastructure or production throughput.

## Discovery and delivery

| Challenge | Source case study | Distinctive problem |
| --- | --- | --- |
| `hld-url-shortener` | [URL Shortener](https://hld.handbook.academy/curriculum/case-studies/url-shortener/) | Unique aliases, link lifecycle, and click accounting |
| `hld-rate-limiter` | [Distributed Rate Limiter](https://hld.handbook.academy/curriculum/case-studies/rate-limiter/) | Atomic quota decisions and retry-safe consumption |
| `hld-notification-system` | [Notification System](https://hld.handbook.academy/curriculum/case-studies/notification-system/) | Push-device token generations, preferences, and delivery retries |
| `hld-web-crawler` | [Web Crawler](https://hld.handbook.academy/curriculum/case-studies/web-crawler/) | URL frontier, host politeness, and deduplication |
| `hld-search-autocomplete` | [Search Autocomplete](https://hld.handbook.academy/curriculum/case-studies/search-autocomplete/) | Locale snapshots, session-deduplicated trends, and policy-filtered prefix ranking |

## Transactional systems

| Challenge | Source case study | Distinctive problem |
| --- | --- | --- |
| `hld-file-sync` | [File Sync Service](https://hld.handbook.academy/curriculum/case-studies/file-sync/) | Version conflicts, block metadata, and incremental synchronization |
| `hld-ticketing-system` | [Ticketing System](https://hld.handbook.academy/curriculum/case-studies/ticketing-system/) | All-or-nothing seat holds and stale confirmation prevention |
| `hld-payment-system` | [Payment System](https://hld.handbook.academy/curriculum/case-studies/payment-system/) | Balanced immutable postings and idempotent payment operations |
| `hld-stock-exchange` | [Stock Exchange](https://hld.handbook.academy/curriculum/case-studies/stock-exchange/) | Deterministic price-time matching and cancellation |
| `hld-hotel-reservation` | [Hotel Reservation System](https://hld.handbook.academy/curriculum/case-studies/hotel-reservation/) | Atomic date-range inventory and booking lifecycle |

## Analytics and control systems

| Challenge | Source case study | Distinctive problem |
| --- | --- | --- |
| `hld-metrics-pipeline` | [Metrics Pipeline](https://hld.handbook.academy/curriculum/case-studies/metrics-pipeline/) | Time-series aggregation, downsampling, and retention |
| `hld-ad-click-aggregation` | [Ad-Click Aggregation](https://hld.handbook.academy/curriculum/case-studies/ad-click-aggregation/) | Deduplicated event-time accounting and late events |
| `hld-job-scheduler` | [Distributed Job Scheduler](https://hld.handbook.academy/curriculum/case-studies/job-scheduler/) | Dependencies, claim lifecycle, and stale-worker fencing |
| `hld-feature-flag-service` | [Feature Flag Service](https://hld.handbook.academy/curriculum/case-studies/feature-flag-service/) | Deterministic targeting, configuration versions, and kill switches |
| `hld-enterprise-rag` | [Enterprise RAG System](https://hld.handbook.academy/curriculum/case-studies/enterprise-rag/) | Tenant/ACL-aware retrieval and document freshness |

## Attribution

Source: The HLD Handbook, handbook.academy / handbook-academy contributors, accessed September 23, 2026. The handbook licenses its prose under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) and its code under MIT. Adapted challenge prose is licensed under CC BY-SA 4.0; adaptations replace the articles' broad system designs with executable Rama contracts and explicitly scoped exclusions. See each challenge for its source and adaptation notes. No affiliation with or endorsement by the handbook or the named services is implied.
