;; Copyright © 2020 Atomist, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns atomist.main
  (:require [atomist.api :as api]
            [cljs.pprint :refer [pprint]]
            [cljs.core.async :refer [<!] :refer-macros [go]]
            [goog.string.format]
            [clojure.data]
            [atomist.cljs-log :as log]
            [atomist.async :refer-macros [go-safe <?]]
            [atomist.github]))

(defn transact-gitops [handler]
  (fn [request]
    (go-safe
      (log/info "transact against %s - %s" (-> request :ref) (-> request :project :path))
      (<? (handler request)))))

(defn add-ref [handler]
  (fn [request]
    (go-safe
     (let [[_ owner repo] (re-find #"(.*)/(.*)" (:gitops-slug request))]
       (api/trace "add-ref")
       (<? (handler (assoc request :ref {:owner owner 
                                         :repo repo 
                                         :branch "main"})))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished)
       (api/mw-dispatch {:on-checkrun.edn (-> (api/finished)
                                              (transact-gitops)
                                              (api/edit-inside-PR
                                                {:branch "k8-deploy-promotion-policy"
                                                 :target-branch "main"
                                                 :title "k8-deploy-promotion-policy"
                                                 :body "Ready to promote"
                                                 :labels ["k8-deploy-promotion-policy"]})
                                              (api/clone-ref)
                                              (add-ref))})
       (api/add-skill-config)
       (api/log-event)
       (api/status))))
