(ns top-users-module.protocol
  "Contract for cumulative top-spending user analytics.")

(defprotocol TopUsers
  (purchase! [this user-id purchase-cents]
    "Append one purchase for a Long user ID and nonnegative Long cents. Purchase IDs are not used or deduplicated.")
  (top-users [this] "Return up to 500 [user-id cumulative-spend-cents] pairs in descending spend order."))
