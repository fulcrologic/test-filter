(ns com.fulcrologic.test-filter.integration-demo
  "Demo code for testing integration test metadata support.")

(defn process-order
  "Processes an order - main business logic."
  [order]
  {:status   :processed
   :order-id (:id order)
   :total    (:total order)})

(defn validate-payment
  "Validates payment information."
  [payment]
  (boolean (and (:card-number payment)
             (:expiry payment)
             (:cvv payment)
             (> (:amount payment) 0))))

(defn send-notification
  "Sends notification to customer."
  [customer-id message]
  {:sent-to customer-id
   :message message})

(defn handle-refund
  "Handles refund processing."
  [order-id amount]
  {:refunded order-id
   :amount   amount})
