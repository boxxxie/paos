(ns paos.wsdl
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [paos.service :as service]
            [paos.wsdl :as wsdl])
  (:import [org.reficio.ws.builder.core SoapOperationImpl Wsdl]
           [org.reficio.ws SoapBuilderException]
           org.reficio.ws.SoapContext
           [java.net MalformedURLException]))

(defn ^SoapContext make-wsdl-context []
  (.build (doto (SoapContext/builder)
            (.exampleContent false)
            (.typeComments true)
            (.valueComments true)
            (.buildOptional true)
            (.alwaysBuildHeaders true))))

(defn ^java.net.URL make-wsdl-url [^String wsdl-path]
  (io/as-url (io/file wsdl-path)))

(defn ^Wsdl make-wsdl [wsdl-url]
  (Wsdl/parse wsdl-url))

(defn make-operation [^SoapContext ctx binding-builder ^SoapOperationImpl operation]
  (let [operation-name  (.getOperationName operation)
        soap-action     (.getSoapAction operation)
        input-template  (.buildInputMessage operation ctx)
        output-template (.buildOutputMessage operation ctx)
        service         (service/->service soap-action input-template output-template)]
    [operation-name service]
    #_[operation-name {:soap-action     (service/get-soap-action service)
                       :input-template  (service/get-request-template service)
                       :input-xml       (service/get-request-xml service)
                       :input-mapping   (service/get-request-mapping service)
                       :output-template (service/get-response-template service)
                       :output-xml      (service/get-response-xml service)
                       :output-mapping  (service/get-response-mapping service)}]))

(defn make-binding
  ([^Wsdl wsdl ^String binding-name]
   (make-binding wsdl binding-name (make-wsdl-context)))
  ([^Wsdl wsdl ^String binding-name ^SoapContext ctx]
   (let [binding-builder (.find (.localPart (.binding wsdl) binding-name))]
     [binding-name {:operations (into {}
                                      (map (partial make-operation ctx binding-builder)
                                           (.getOperations binding-builder)))
                    :url (first (.getServiceUrls binding-builder))}])))

(defn file->wsdl [file-name]
  (-> file-name
      make-wsdl-url
      make-wsdl))

(defn net-url->wsdl [net-url]
  (-> net-url
      (java.net.URL.)
      make-wsdl))

(defn wsdl-content->wsdl [wsdl-content]
  (let [file-name (java.io.File/createTempFile "service" ".wsdl")]
    (with-open [file (clojure.java.io/writer file-name)]
      (binding [*out* file]
        (println wsdl-content)))
    (-> file-name
        make-wsdl-url
        make-wsdl)))

(defn ->wsdl [path-or-content]
  (cond
    (.exists (io/file path-or-content))
    (file->wsdl path-or-content)

    (try
      (java.net.URL. path-or-content)
      (catch MalformedURLException e
        false))
    (net-url->wsdl path-or-content)

    :otherwise (wsdl-content->wsdl path-or-content)))

(defn parse [wsdl]
  (let [wsdl (->wsdl wsdl)
        ctx (make-wsdl-context)]
    (into {}
          (map (fn [binding]
                 (try
                   (make-binding wsdl
                                 (.getLocalPart binding)
                                 ctx)
                   (catch SoapBuilderException e
                     [(.getLocalPart binding) nil])))
               (.getBindings wsdl)))))

(comment

  (parse "http://www.thomas-bayer.com/axis2/services/BLZService?wsdl")
  (parse (slurp "/Users/delaguardo/Downloads/BLZService.wsdl"))
  (parse "/Users/delaguardo/Downloads/BLZService.wsdl")

  (require '[clj-http.client :as client])
  (require '[paos.service :as service])
  (require '[paos.wsdl :as wsdl])

  (let [soap-service (wsdl/parse "http://www.thomas-bayer.com/axis2/services/BLZService?wsdl")
        srv          (get-in soap-service ["BLZServiceSOAP11Binding" :operations "getBank"])
        soap-url     (get-in soap-service ["BLZServiceSOAP11Binding" :url])
        soap-action  (service/soap-action srv)
        mapping      (service/request-mapping srv)
        context      (assoc-in mapping ["Envelope" "Body" "getBank" "blz" :__value] "28350000")
        body         (service/wrap-body srv context)
        parse-fn     (partial service/parse-response srv)]
    (-> soap-url
        (client/post {:content-type "text/xml"
                      :body         body
                      :headers      {"SOAPAction" soap-action}})
        :body
        parse-fn)))
