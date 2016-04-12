(ns featured-gml.api
  (:require [featured-gml.runner :as runner]
            [featured-gml.zip :as zip]
            [featured-gml.filesystem :as fs]
            [ring.util.response :as r]
            [ring.middleware.json :as middleware]
            [clj-time [core :as t] [local :as tl]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [schema.core :as s]
            [org.httpkit.client :as http-kit]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.java.io :as io])
  (:gen-class)
  (:import (org.joda.time DateTime)
           (java.util.zip ZipInputStream)))

(extend-protocol cheshire.generate/JSONable
  DateTime
  (to-json [t jg] (.writeString jg (str t))))

(defn- translate-file-from-stream [reader dataset mapping validity out-file]
  "Read from reader, xml2json translate the content"
  (with-open [writer (io/writer out-file)]
    (runner/translate dataset mapping validity reader writer)))

(defn- translate-file-from-zipentry [dataset mapping validity zipstream entry]
  "Transform a single entry in a zip-file. Returns the location where the result is saved on the filesystem"
  (let [resulting-file (fs/create-target-file (.getName entry))]
    (log/debug "Going to transform zip-entry" (.getName entry) "to" (.getPath resulting-file))
    (translate-file-from-stream zipstream dataset mapping validity resulting-file)
    (.closeEntry zipstream)
    resulting-file))

(defn- translate-from-zipstream [stream dataset mapping validity]
  "Transforms entries in a zip file and returns a vector with the transformed files"
  (with-open [zipstream (ZipInputStream. stream)]
    (into [] (map (partial translate-file-from-zipentry
                              dataset
                              mapping
                              validity
                              zipstream) (zip/entries zipstream)))))


(defn translate-entire-stream [zipped stream dataset mapping validity original-filename]
  "Transforms a file or zip-stream and returns a vector with the transformed files"
  (if zipped
    (translate-from-zipstream stream dataset mapping validity)
    (let [target-filename (fs/create-target-file original-filename)]
      (do (translate-file-from-stream stream dataset mapping validity target-filename))
      [target-filename])))

(defn extract-name-from-uri [uri]
  "Extract the requested-path from an uri"
  (last (re-find #"(\w+).(?:\w+)$" uri)))

(defn download-file-as-stream [uri]
  "Download uri and get the body as stream. Returns :error key if an error occured"
  (let [{:keys [status body headers]} @(http-kit/get uri {:as :stream})]
    (if (nil? status)
      [:download-error (str "No response when trying to download: " uri)]
      (if (< status 300)
        {:zipped (re-find #"zip" (:content-type headers))
         :dlstream body
         :filename (extract-name-from-uri uri)}
        {:download-error (str "No success: Got a statuscode " status " when downloading " uri )}))))

(defn process-downloaded-xml2json-data [datasetname mapping validity zipped data-stream original-filename]
  (log/info "Going to transform dataset" datasetname)
  (let [unzipped-files (translate-entire-stream zipped
                                                data-stream
                                                datasetname
                                                mapping
                                                validity
                                                original-filename)
        zipped-files (doall (map zip/zip-file unzipped-files))]
    (log/info "Transformation of" (count unzipped-files) "file(s) done")
    (fs/delete-files unzipped-files)
    (log/info "Unzipped files removed")
    (log/info "Zipped" (count zipped-files) "file(s) in store-directory")
    {:json-files (map #(.getName %) zipped-files)}))

(defn process-xml2json [dataset mapping uri validity]
  "Proces the request and  zip the result in a zip on the filesystem and return a reference to this zip-file"
  (let [result (download-file-as-stream uri)]
     (if (:download-error result)
      (r/response {:status 400 :body (:download-error result)})
      (process-downloaded-xml2json-data dataset mapping validity (:zipped result) (:dlstream result) (:filename result)))))

(defn async-process-xml2json [cc callback-uri dataset mapping file validity]
  "Async handling of xml2json. Will do a callback once completed on the provided callback-uri"
  (let [rc (thread (process-xml2json dataset mapping file validity))]
    (go (>! cc [callback-uri (<! rc)]))
    (log/info "Async job started. Callback will be done on" callback-uri)
    {:status 200 :body (str "Accepted will do callback on " callback-uri)}))

(defn handle-xml2json-req [cc req]
  "Get the properties from the request and start an sync or async xml2json operation (depending on if a callback-uri is provided)"
  (future (fs/cleanup-old-files (* 3600 * 48)))
  (r/response
    (let [dataset (:dataset (:body req))
          mapping (:mapping (:body req))
          file (:file (:body req))
          validity (:validity (:body req))
          callback-uri (:callback-uri (:body req))]
      (if (some str/blank? [dataset mapping file validity])
        {:status 400 :body "dataset, mapping, file and validity are all required"}
        (if callback-uri
          (async-process-xml2json cc callback-uri dataset mapping file validity)
          (process-xml2json dataset mapping file validity))))))

(defn handle-getjson-req [req]
  "Stream a json file identified by uuid"
   (let [file (:file (:params req))]
     (log/debug "Request for" file)
     (if-let [local-file (fs/get-file file)]
       {:headers {"Content-Description"       "File Transfer"
                  "Content-type"              "application/octet-stream"
                  "Content-Disposition"       (str "attachment;filename=" (.getName local-file))
                  "Content-Transfer-Encoding" "binary"}
        :body    local-file}
       {:status 500, :body "No such file"})))

(defn handler [cc]
  (defroutes handler
             (context "/api" []
               (GET "/info" [] (r/response {:version (runner/implementation-version)}))
               (GET "/ping" [] (r/response {:pong (tl/local-now)}))
               (GET "/get/:file" request handle-getjson-req)
               (POST "/xml2json" request (partial handle-xml2json-req cc)))
             (route/not-found "Featured-gml: Unknown operation. Try /api/info, /api/ping, /api/get, /api/xml2json")))

(defn wrap-exception-handling
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log/error e)
        {:status 400 :body (.getMessage e)}))))

(defn- callbacker [uri result]
  (http-kit/post uri {:body (cheshire.core/generate-string result)
                      :headers {"Content-Type" "application/json"}}))
(def app
  (let [cc (chan 10)]
    (go (while true (apply callbacker (<! cc))))
    (-> (handler cc)
        (middleware/wrap-json-body {:keywords? true})
        middleware/wrap-json-response
        wrap-exception-handling)))
