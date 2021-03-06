(ns chronograph.import.mdapi
  (:require [chronograph.data :as data]
            [chronograph.core :as core]
            [chronograph.util :as util])
  (:require [clj-http.client :as client])
  (:require [crossref.util.config :refer [config]]
            [crossref.util.date :as crdate]
            [crossref.util.doi :as crdoi])
  (:require [korma.core :as k])
  (:require [clj-time.core :as t]
            [clj-time.periodic :as time-period])
  (:require [clj-time.coerce :as coerce]
            [clj-time.format :refer [parse formatter unparse]])
  (:require [robert.bruce :refer [try-try-again]])
  (:require [clojure.core.async :as async :refer [<!! <! >! go chan go-loop close!]]))

; (def works-endpoint "http://api.crossref.org/v1/works")
; (def api-page-size 1000)

; Temporary custom values. 
(def works-endpoint "http://api.crossref.org/v1/works")
(def members-endpoint "http://api.crossref.org/v1/members")
(def api-page-size 1000)
(def members-api-page-size 100)
(def transaction-chunk-size 10000)
(def sample-size 100)

(defn get-metadata [doi]
  (try 
    (let [response (client/get (str works-endpoint "/" doi) {:as :json})
          message (-> response :body :message)
          title (first (:title message))]
      {:title title})
    (catch Exception _ {})))

(defn fetch-and-parse [url]
  (prn "GET URL" url)
  (let [response (try-try-again {:sleep 5000 :tries :unlimited} #(client/get url {:as :json}))
        items (-> response :body :message :items)
        ; transform into sequence of inputs to insert-event
        transformed (mapcat (fn [item]
                              (let [the-doi (:DOI item)
                                    ; list of date parts in CrossRef date format
                                    issued-input (-> item :issued :date-parts first)
                                    ; CrossRef date native format
                                    ; Issued may be missing, e.g. 10.1109/lcomm.2012.030912.11193
                                    ; Or may be a single null in a vector.
                                    issued-input-ok (and (not-empty issued-input) (every? number? issued-input))
                                    
                                    issued (when issued-input-ok (apply crdate/crossref-date issued-input))
                                    ; Nominal, but potentially lossily converted format.
                                    ; Coerce will work with the the DateTimeProtocol
                                    issuedDate (when issued-input-ok (coerce/to-sql-date (crdate/as-date issued)))
                                    ; Non-lossy, but string representation of date.
                                    issuedString (str issued)
                                    
                                    ; updated
                                    updatedInput (-> item :deposited :date-parts first)
                                    updated (apply crdate/crossref-date updatedInput)
                                    updatedDate (coerce/to-sql-date (crdate/as-date updated))
                                    
                                    ; Crossmark updates (i.e. this DOI updates another)
                                    
                                    crossmark-update-to (-> item :update-to)
                                    ; seq of [doi, type, source, date, count, DOI that wasn updated]
                                    ; These are events for the *other* DOI.
                                    crossmark-updates (map (fn [update] [(:DOI update)
                                                                         :crossmark-update-published
                                                                         :CrossRefMetadata
                                                                         (crdate/as-date (apply crdate/crossref-date (-> update :updated :date-parts first)))
                                                                         1
                                                                         the-doi
                                                                         nil
                                                                         nil]) crossmark-update-to)
                                    
                                    ; results are mapcatted
                                    results []
                                    ; DOI metadata updated
                                    results (conj results [the-doi :updated :CrossRefMetadata updatedDate 1 nil nil nil])
                                    ; and DOI issued (published), if the deposit date was OK
                                    results (if issued-input-ok
                                              (conj results [the-doi :issued :CrossRefMetadata issuedDate 1 issuedString nil nil])
                                              results)
                                    ; and all CrossMark updates, if any
                                    results (concat results crossmark-updates)]
                                  results))
                            items)
        to-insert (remove nil? transformed)]
    
    (prn "insert chunk...")
    (doseq [subchunk (partition-all transaction-chunk-size to-insert)]
      (prn "insert subchunk... ")
      ; DONE HS
      (data/insert-events-chunk subchunk))
    (prn "done")))

(defn get-dois-updated-since [date]
  (let [base-q (if date 
          {:filter (str "from-update-date:" (unparse core/yyyy-mm-dd date))}
          {:filter ""})
        results (try-try-again {:sleep 5000 :tries :unlimited} #(client/get (str works-endpoint \? (client/generate-query-string (assoc base-q :rows 0))) {:as :json}))
        num-results (-> results :body :message :total-results)
        ; Extra page just in case.
        num-pages (+ (quot num-results api-page-size) 2)
        page-queries (map #(str works-endpoint \? (client/generate-query-string (assoc base-q
                                                                                  :rows api-page-size
                                                                                  :offset (* api-page-size %)))) (range num-pages))]
        (doseq [url page-queries]
          (fetch-and-parse url))))


(defn run-doi-extraction-new-updates []
  (let [last-run-date (data/get-last-run-date)
        now (t/now)]
    (get-dois-updated-since last-run-date)    
    (data/set-last-run-date! now)))

(defn process-member [member-id]
  ; Do each member in background concurrently
  (locking *out* (prn "Member id" member-id))
  (let [works-url (str members-endpoint "/" member-id "/works" \? (client/generate-query-string {:sample sample-size :rows sample-size}))
        works-results 
        (try-try-again {:sleep 5000 :tries :unlimited}
               #(client/get works-url {:as :json}))
        works (-> works-results :body :message :items)
        dois (map :DOI works)
        domains (mapcat     
                  (fn [doi]
                    (let [url (str "http://dx.doi.org/" doi)
                          result (try (try-try-again {:sleep 1000 :tries 2}
                                       #(client/get url  {:socket-timeout 5000  :conn-timeout 5000 :throw-exceptions true}))
                                   (catch Exception _ nil))
                          ; drop first, it's always "dx.doi.org"
                          redirects (rest (:trace-redirects result))
                          all-domains (when result (into #{}
                                                         (map (fn [url]
                                                          (let [host (.getHost (new java.net.URL url))
                                                                [subdomain true-domain etld] (util/get-main-domain host)
                                                                domain-part (str true-domain "." etld)]
                                                              domain-part)) redirects)))]
                      (or all-domains [])))
                  dois)
        unique-domains (into #{} domains)]
      unique-domains))

(def num-member-workers 20)

(defn update-member-domains []
  (let [results (try-try-again {:sleep 5000 :tries :unlimited}
                               #(client/get (str members-endpoint \? (client/generate-query-string {:rows 0})) {:as :json}))
        num-results (-> results :body :message :total-results)
        ; Extra page just in case.
        num-pages (+ (quot num-results members-api-page-size) 2)
        page-queries (map #(str members-endpoint \? (client/generate-query-string (assoc {}
                                                                                  :rows members-api-page-size
                                                                                  :offset (* members-api-page-size %)))) (range num-pages))
        ; Member IDs
        member-channel (chan)
        ; Return channel of [member-id domains]
        member-domains-channels (apply vector (map (fn [i] (chan)) (range num-member-workers)))
        member-domains-channel (async/merge member-domains-channels)]
        
        ; Run n workers that output on their own channels.
        (doseq [ch member-domains-channels]
          (go
            (loop [member-id (<! member-channel)]
              (when member-id
                ; Fetch domains for this member.
                (let [domains (process-member member-id)]
                  (>! ch [member-id domains])))
                (recur (<! member-channel)))
              (close! ch)))
        
        ; For each page of members spool the IDs into member-channel. 
        (go
          (doseq [page-url page-queries]
            (let [results (try-try-again {:sleep 5000 :tries 3}
                           #(client/get page-url {:as :json}))
                  members (-> results :body :message :items)]
              
            ; Spool member ids
            (doseq [member members]
              (prn "Spool" (:id member))
              (>! member-channel (:id member))))))
              
        ; Pick up the domains synchronously coming back over the workers' channels.
        (loop [[member-id member-domains] (<!! member-domains-channel)]
          (locking *out* (prn "Insert domains for member" member-id "domains" member-domains))
          (data/insert-member-domains member-id member-domains)
          (recur (<!! member-domains-channel)))))
                
