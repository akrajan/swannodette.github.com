(ns blog.responsive.core
  (:refer-clojure :exclude [map filter distinct remove])
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]]
    [cljs.core.match.macros :refer [match]])
  (:require
    [cljs.core.match]
    [cljs.core.async :refer [>! <! alts! put! sliding-buffer chan]]
    [blog.utils.helpers :refer [index-of]]
    [blog.utils.dom
     :refer [by-id set-html! add-class! remove-class!]]
    [blog.utils.reactive
     :refer [map filter distinct remove hover hover-child]])
  (:import
    goog.events.BrowserEvent))

;; -----------------------------------------------------------------------------
;; Declarations

(def ENTER 13)
(def UP_ARROW 38)
(def DOWN_ARROW 40)

(def KEYS #{UP_ARROW DOWN_ARROW ENTER})

(defn el-matcher [el]
  (fn [other] (identical? el other)))

;; -----------------------------------------------------------------------------
;; Interface representation protocols

(defprotocol IHighlightable
  (-highlight! [list n])
  (-unhighlight! [list n]))

(defprotocol ISelectable
  (-select! [list n])
  (-unselect! [list n]))

;; -----------------------------------------------------------------------------
;; Event stream coordination

(defn handle-change-event [list idx key]
  (let [cnt (count list)]
    (match [idx key]
      [::none :next    ] 0
      [::none :previous] (dec cnt)
      [_      :next    ] (mod (inc idx) cnt)
      [_      :previous] (mod (dec idx) cnt))))

(defn handle-event [e cur list]
  (when (number? cur)
    (-unhighlight! list cur))
  (if (= e :clear)
    ::none
    (let [n (if (number? e) e (handle-change-event list cur e))]
      (-highlight! list n)
      n)))

(defn highlighter [in list]
  (let [out (chan)]
    (go (loop [highlighted ::none]
          (let [e (<! in)]
            (if (or (#{:next :previous :clear} e) (number? e))
              (let [highlighted (handle-event e highlighted list)]
                (>! out highlighted)
                (recur highlighted))
              (do (>! out e)
                (recur highlighted))))))
    out))

(defn selector [in list data]
  (let [out (chan)]
    (go (loop [highlighted ::none selected ::none]
          (let [e (<! in)]
            (if (= e :select)
              (do
                (when (number? selected)
                  (-unselect! list selected))
                (-select! list highlighted)
                (>! out [:select (nth data highlighted)])
                (recur highlighted highlighted))
              (do
                (>! out e)
                (if (or (= e ::none) (number? e))
                  (recur e selected)
                  (recur highlighted selected)))))))
    out))

;; =============================================================================
;; Example constructor

(def create-example [id ui event-fn render-fn ctor-fn]
  (let [hc (hover (by-id id))
        prevent-default? (atom false)
        raw (event-fn prevent-default?)
        {events :chan ctrl :control} (toggle raw)
        changes (ctor-fn events ui)]
  (render-fn)
  (go
    (>! ctrl false)
    (while true
      (when (= (<! hc) :enter)
        (>! ctrl true)
        (reset! prevent-default? true)
        (loop []
          (let [[e c] (alts! [hc changes])]
            (cond
              (= e :leave) (do (>! ctrl false)
                             (reset! prevent-default? false))
              (= c changes) (do (render-fn)
                              (recur))
              :else (recur)))))))))

;; =============================================================================
;; Example 0

(defn ex0-key-events [prevent-default?]
  (->> (listen js/document :keydown prevent-default?)
    (map key-event->keycode)
    (filter KEYS)
    (map key->keyword)))

(def ex0-ui (array "   Alan Kay"
                   "   J.C.R. Licklider"
                   "   John McCarthy"))

(defn ex0-render! []
  (set-html (by-id "ex0-ui") (.join ex0-ui "\n")))

(extend-type array
  IHighlightable
  (-highlight! [list n]
    (aset list n (set-char (aget list n) 0 ">")))
  (-unhighlight! [list n]
    (aset list n (set-char (aget list n) 0 " ")))
  
  ISelectable
  (-select! [list n]
    (aset list n (set-char (aget list n) 1 "*")))
  (-unselect! [list n]
    (aset list n (set-char (aget list n) 1 " "))))

(defn set-char [s i c]
  (str (.substring s 0 i) c (.substring s (inc i))))

(create-example "ex0" ex0-ui ex0-key-events ex0-render! highlighter)

;; =============================================================================
;; Example 1

(defn ex1-key-events [prevent-default?]
  (->> (events js/document :keydown prevent-default?)
    (map key-event->keycode)
    (filter KEYS)
    (map key->keyword)))

(def ex1-ui (array "   Smalltalk"
                   "   Lisp"
                   "   Prolog"
                   "   ML"))

(defn ex1-render! []
  (set-html (by-id "ex1-ui") (.join ex1-ui "\n")))

(create-example "ex1" ex1-ui ex1-key-events ex1-render! highlighter)

;;=============================================================================
;; Example 2

(defn by-tag-name [el tag]
  (prim-seq (.getElementsByTagName el tag)))

(defn ex2-events [prevent-default?]
  (let [ex2-list (hover-chan ex2-list "li")]
    (fan-in
      [(->> (events js/document :keydown prevent-default?)
         (map key-event->keycode)
         (filter KEYS)
         (map key->keyword))
       (hover-child ex2-list "li")])))

(extend-type js/HTMLUListElement
  ICounted
  (-count [list]
    (count (by-tag-name list "li")))

  IHighlightable
  (-highlight! [list n]
    (add-class! (nth (by-tag-name list "li") n) "highlighted"))
  (-unhighlight! [list n]
    (remove-class! (nth (by-tag-name list "li") n) "highlighted"))
  
  ISelectable
  (-select! [list n]
    (add-class! (nth (by-tag-name list "li") n) "selected"))
  (-unselect! [list n]
    (remove-class! (nth (by-tag-name list "li") n) "selected")))

(defn ex2-key-events [prevent-default?]
  (->> (listen js/document :keydown prevent-default?)
    (map key-event->keycode)
    (filter KEYS)
    (map key->keyword)))

(create-example "ex1" ex1-ui ex1-key-events ex1-render! highlighter)