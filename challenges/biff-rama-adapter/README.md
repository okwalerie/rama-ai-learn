# Biff database adapter

Build a [Biff](https://github.com/jacobobryant/biff) database adapter backed
by a Rama module. Biff apps keep their data in your module and reach it
through the interfaces Biff defines for database adapters: `biff.core`
lifecycle and KV functions, `:biff.core/wrap-db-snapshot`,
`:biff.core/on-tx`, `biff.fx` handlers and `biff.graph` resolvers. The
adapter runs in each app process ("node"). Several nodes can share one
deployed module.

The tests use `com.biffweb/core` and `com.biffweb/graph` 2.0.5, which are on
the classpath. Write
`implementations/biff-rama-adapter/src/biff_rama_adapter/module.clj`.

## Factory

`biff-rama-adapter.module/create-module` takes no arguments and returns
`{:module <Rama module> :biff-module <fn>}`. The tests launch `:module`
themselves. `(biff-module)` and
`(biff-module :biff.rama/columns columns)` each return a `biff.core` module
map with:

- `:biff.core/id` `:biff.rama/module`.
- `:biff.core/start`, `(fn [ctx]) => ctx`. `ctx` contains
  `:biff.rama/cluster`, a Rama cluster manager on which the module is already
  launched. Never deploy or launch modules here. Add `:biff.core/kv-get`,
  `:biff.core/kv-set`, `:biff.core/kv-list` and
  `:biff.core/wrap-db-snapshot` to the returned ctx, plus any keys your
  handlers need.
- `:biff.core/stop`, `(fn [ctx])`. Release the node's client resources. A
  stopped node gets no further `on-tx` calls.
- `:biff.fx/handlers` with `:biff.rama.fx/transact` and
  `:biff.rama.fx/entity`. Each takes the started system map as `ctx`.
- `:biff.graph/resolvers`, only when `columns` is given.

The tests start nodes with `biff.core/start`, so `ctx` also carries
`:biff.core/on-tx`. Other `biff.core` modules may sit in the same system.

## Entities

Entities live in tables. A table is an unqualified keyword such as `:user`.
An entity is a map whose keys are keywords namespaced by its table
(`:user/email`). The key `:<table>/id` is its id, which can be any EDN
value except `nil`. Tables are separate id spaces. Values are EDN that
round-trip through `pr-str` and `clojure.edn/read-string`.

`(transact ctx ops)` runs one transaction. `ops` is a non-empty sequence of:

- `[:put table doc]`: replace the entity with `doc`.
- `[:patch table doc]`: merge `doc` into the entity, creating it if absent.
  An attribute whose value is `nil` is removed.
- `[:delete table id]`: remove the entity. Deleting an absent entity is not
  an error.

Every `doc` must contain its id, and all its keys must be in the table's
namespace. A transaction with any malformed op throws and changes nothing.
Ops in one transaction apply in order. A transaction is atomic: no read sees
some of its writes without all of them. Concurrent transactions behave as if
run one at a time in some order. `transact` returns once the transaction is
visible to reads on the same node.

`(entity ctx table id)` returns the entity map, or `nil` if it is absent.

## Snapshots

`:biff.core/wrap-db-snapshot` is `(fn [f]) => (fn [ctx])`. Every `entity`
read and resolver call made with the ctx that the wrapped function receives
sees one database state. That state ignores transactions that finish after
the snapshot starts and includes every transaction that `transact` on this
node returned before it started. `biff.graph/query` wraps its execution with
this function when it is in the ctx.

## Notifications

After a transaction becomes visible, call `(on-tx ctx)` on every started
node, with that node's started ctx. Reads on a node after its `on-tx` call
see the transaction. On the writing node, call `on-tx` before `transact`
returns. A node may get one call for several transactions, but it gets no
calls while no transactions happen.

## KV store

Implement `:biff.core/kv-set`, `:biff.core/kv-get` and
`:biff.core/kv-list` as the
[biff.core schema](https://github.com/jacobobryant/biff/blob/master/libs/core/docs/schema.md)
defines them:

- `(kv-set ctx namespace key value)` returns `nil`. A `nil` value deletes
  the key. Any other value, including `false`, is stored.
- `(kv-get ctx namespace key)` returns the value, or `nil` when unset.
- `(kv-list ctx namespace)` and `(kv-list ctx namespace prefix)` return the
  set keys in ascending order. A string prefix keeps only keys that start
  with it; a `nil` prefix keeps all.

`namespace` must be a qualified keyword and `key` a string; otherwise
throw. Namespaces are isolated. A KV read after `kv-set` returns on any node
sees the write. KV writes need not call `on-tx` or join snapshots.

## Resolvers

`columns` maps every non-id attribute to an options map, for example
`{:user/email {} :user/pet-id {:ref :pet/id} :pet/owner {:ref :user/id}}`.
Generate one batch resolver per table, with input `[:<table>/id]`, that
returns the entity's attributes present in `columns`:

- A plain attribute is a scalar output.
- A `:ref` attribute whose name ends in `-id` is output as its value and as
  a join without the suffix: `:user/pet-id 10` also gives
  `:user/pet {:pet/id 10}`.
- Any other `:ref` attribute is output only as a join:
  `:pet/owner {:user/id 1}`.

Absent entities and attributes are left unresolved. Resolvers read through
the snapshot of the ctx they receive.

## Non-functional requirements

- Reading an entity costs the same after many updates as after a few.
- Concurrent `transact` calls do not wait on each other: many in parallel
  take far less time than the same number run one after another.

Run the private harness with `clojure -X:test-private-harness` from this
directory.
