;; Copyright © 2021 Atomist, Inc.
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
            [clojure.string :as s]
            [cljs-node-io.core :as io]
            [goog.string.format]
            [goog.string :as gstring]
            [clojure.data]
            [atomist.cljs-log :as log]
            [atomist.local-runner :as lr]
            [atomist.async :refer-macros [go-safe <?]]
            [atomist.github]))

(comment
  (s/replace "image: gcr.io/personalsdm-216019/cj-test-docker:758deb5c0d127901fddc6ff49be76561dbc9e131\n"
             #"image: ([\w./@:\-_]*)"
             (fn [[_ v]]
               (log/info "updating " v)
               (gstring/format "image: %s" "yo"))))

(defn transact-gitops [handler]
  (fn [request]
    (go-safe
     (log/infof "transact against %s - %s" (-> request :ref) (-> request :project :path))
     (let [f (io/file (-> request :project :path) "deploy/base/clj-test-deployment.yaml")]
       (io/spit f (-> (io/slurp f)
                      (s/replace #"image: ([\w./@:\-_]*)" (fn [[_ v]]
                                                            (log/info "updating " v)
                                                            (gstring/format "image: %s" (:atomist/target-image request))))))
       (<? (handler (assoc request
                           :atomist/status {:code 0 :reason "GitOps transaction"})))))))

(defn add-ref [handler]
  (fn [request]
    (go-safe
     (let [[_ owner repo] (re-find #"(.*)/(.*)" (:gitops-slug request))]
       (api/trace "add-ref")
       (<? (handler (assoc request :ref {:owner owner
                                         :repo repo
                                         :branch "main"})))))))

(defn add-target-image [handler]
  (fn [request]
    (go-safe
     (let [[_ {:docker.image/keys [digest repository]}] (-> request :subscription :result first)]
       (api/trace (gstring/format "add-target-image %s %s" digest repository))
       (<? (handler (assoc request
                           :atomist/target-image
                           (gstring/format
                            "%s/%s@%s"
                            (:docker.repository/host repository)
                            (:docker.repository/repository repository)
                            digest))))))))

(defn error-handler [handler]
  (fn [request]
    (go
      (try
        (<? (handler request))
        (catch :default ex
          (assoc request :atomist/status {:code 1 :reason (str ex)}))))))

(defn ^:export handler
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   (-> (api/finished)
       (api/mw-dispatch {:on-checkrun.edn (-> (api/finished)
                                              (transact-gitops)
                                              (add-target-image)
                                              (api/edit-inside-PR
                                               {:branch "k8-deploy-promotion-policy"
                                                :target-branch "main"
                                                :title "k8-deploy-promotion-policy"
                                                :body "Ready to promote"
                                                :labels ["k8-deploy-promotion-policy"]})
                                              (api/clone-ref)
                                              (api/extract-github-token)
                                              (add-ref))})
       (error-handler)
       (api/add-skill-config)
       (api/log-event)
       (api/status))))

(comment
  (enable-console-print!)
  (lr/callEventHandler #js {} handler))
