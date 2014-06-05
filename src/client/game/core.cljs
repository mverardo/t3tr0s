(ns client.game.core
  (:require-macros
    [cljs.core.async.macros :refer [go alt!]])
  (:require
    [cljs.reader :refer [read-string]]
    [client.game.board :refer [piece-fits?
                               rotate-piece
                               start-position
                               empty-board
                               get-drop-pos
                               get-rand-piece
                               get-rand-diff-piece
                               write-piece-to-board
                               write-piece-behind-board
                               create-drawable-board
                               get-filled-row-indices
                               clear-rows
                               collapse-rows
                               highlight-rows
                               write-to-board
                               n-rows
                               n-cols
                               rows-cutoff
                               next-piece-board]]
    [client.game.rules :refer [get-points
                               level-up?
                               get-level-speed]]
    [client.game.paint :refer [delete-opponent-canvas!
                               create-opponent-canvas!
                               size-canvas!
                               cell-size
                               draw-board!]]
    [client.game.multiplayer :refer [opponent-scale]]
    [client.game.vcr :refer [vcr toggle-record! record-frame!]]
    [client.socket :refer [socket]]
    [cljs.core.async :refer [close! put! chan <! timeout unique alts!]]))

(enable-console-print!)

; alias the jquery variable
(def $ js/$)

;;------------------------------------------------------------
;; STATE OF THE GAME
;;------------------------------------------------------------

(def battle
  "Boolean flag signaling whether we are in solo or battle mode."
  (atom false))

(def state
  "The state of the game."
  (atom nil))

(defn init-state!
  "Set the initial state of the game."
  []
  (reset! state {:next-piece nil
                 :piece nil
                 :position nil
                 :board empty-board

                 :theme 0

                 :score 0
                 :level 0
                 :level-lines 0
                 :total-lines 0

                 :soft-drop false

                 :quit false
                 :quit-chan (chan)}))

; required for pausing/resuming the gravity routine
(def pause-grav (chan))
(def resume-grav (chan))

;;------------------------------------------------------------
;; STATE MONITOR
;;------------------------------------------------------------

(defn drawable-board
  "Draw the current state of the board."
  []
  (let [{piece :piece
         [x y] :position
         board :board} @state]
    (create-drawable-board piece x y board)))

(defn make-redraw-chan
  "Create a channel that receives a value everytime a redraw is requested."
  []
  (let [redraw-chan (chan)
        request-anim #(.requestAnimationFrame js/window %)]
    (letfn [(trigger-redraw []
              (when-not (:quit @state)
                (put! redraw-chan 1)
                (request-anim trigger-redraw)))]
      (request-anim trigger-redraw)
      redraw-chan)))

(defn go-go-draw!
  "Kicks off the drawing routine."
  []
  (let [redraw-chan (make-redraw-chan)]
    (go
      (loop [board nil theme nil]
        (let [[_ c] (alts! [(:quit-chan @state) redraw-chan])]
          (if (= c redraw-chan)
            (let [new-board (drawable-board)
                  new-theme (:theme @state)
                  next-piece (:next-piece @state)]
              (when (or (not= board new-board)
                        (not= theme new-theme))
                (.emit @socket "board-update" (pr-str {:level (:level @state)
                                                       :board new-board}))
                (draw-board! "game-canvas" new-board cell-size new-theme rows-cutoff)
                (draw-board! "next-canvas" (next-piece-board next-piece) cell-size new-theme)
                (if (:recording @vcr)
                  (record-frame!)))
              (recur new-board new-theme))))))))

;;------------------------------------------------------------
;; Game-driven STATE CHANGES
;;------------------------------------------------------------

(defn go-go-game-over!
  "Kicks off game over routine. (and get to the chopper)"
  []
  (go ;exitable
    (doseq [y (reverse (range n-rows))
            x (range n-cols)]
      (if (even? x)
        (<! (timeout 2)))
      (swap! state update-in [:board] #(write-to-board x y "H0" %)))))

(defn spawn-piece! 
  "Spawns the given piece at the starting position."
  [piece]
    (swap! state assoc :piece piece
                       :position start-position)
    (put! resume-grav 0))

(defn try-spawn-piece!
  "Checks if new piece can be written to starting position."
  []
  (let [piece (or (:next-piece @state) (get-rand-piece))
        next-piece (get-rand-diff-piece piece)
        [x y] start-position
        board (:board @state)]

    (swap! state assoc :next-piece next-piece)

    (if (piece-fits? piece x y board)
      (spawn-piece! piece)
      (go ;exitable
        ; Show piece that we attempted to spawn, drawn behind the other pieces.
        ; Then pause before kicking off gameover animation.
        (swap! state update-in [:board] #(write-piece-behind-board piece x y %))
        (<! (timeout (get-level-speed (:level @state))))
        (go-go-game-over!)))))

(defn display-points!
  []
  (.html ($ "#score") (str "Score: " (:score @state)))
  (.html ($ "#level") (str "Level: " (:level @state)))
  (.html ($ "#lines") (str "Lines: " (:total-lines @state))))

(defn update-points!
  [rows-cleared]
  (let [n rows-cleared
        level (:level @state)
        points (get-points n (inc level))
        level-lines (+ n (:level-lines @state))]

    ; update the score before a possible level-up
    (swap! state update-in [:score] + points)

    (if (level-up? level-lines)
      (do
        (swap! state update-in [:level] inc)
        (swap! state assoc :level-lines 0))
      (swap! state assoc :level-lines level-lines))

    (swap! state update-in [:total-lines] + n))

  (display-points!))

(defn collapse-rows!
  "Collapse the given row indices."
  [rows]
  (let [n (count rows)
        board (collapse-rows rows (:board @state))]
    (swap! state assoc :board board)
    (update-points! n)))

(defn go-go-collapse!
  "Starts the collapse animation if we need to, returning nil or the animation channel."
  []
  (let [board (:board @state)
        rows (get-filled-row-indices board)
        flashed-board (highlight-rows rows board)
        cleared-board (clear-rows rows board)]

    (when-not (zero? (count rows))
      (go ; no need to exit this (just let it finish)
        ; blink n times
        (doseq [i (range 3)]
          (swap! state assoc :board flashed-board)
          (<! (timeout 170))
          (swap! state assoc :board board)
          (<! (timeout 170)))

        ; clear rows to create a gap, and pause
        (swap! state assoc :board cleared-board)
        (<! (timeout 220))

        ; finally collapse
        (collapse-rows! rows)))))

(defn lock-piece!
  "Lock the current piece into the board."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)]
    (swap! state assoc :board (write-piece-to-board piece x y board)
                       :piece nil
                       :soft-drop false)
    (put! pause-grav 0)

    ; If collapse routine returns a channel...
    ; then wait for it before spawning a new piece.
    (if-let [collapse-anim (go-go-collapse!)]
      (go
        (<! collapse-anim)
        (<! (timeout 100))
        (try-spawn-piece!))
      (try-spawn-piece!))))

(defn apply-gravity!
  "Move current piece down 1 if possible, else lock the piece."
  []
  (let [piece (:piece @state)
        [x y] (:position @state)
        board (:board @state)
        ny (inc y)]
    (if (piece-fits? piece x ny board)
      (swap! state assoc-in [:position 1] ny)
      (lock-piece!))))

(defn go-go-gravity!
  "Starts the gravity routine."
  []
  ; Make sure gravity starts in paused mode.
  ; Spawning the piece will signal the first "resume".
  (put! pause-grav 0)

  (go
    (loop []
      (let [soft-speed 25
            level-speed (get-level-speed (:level @state))
            speed (if (:soft-drop @state)
                    (min soft-speed level-speed)
                    level-speed)
            time-chan (timeout speed)
            quit-chan (:quit-chan @state)
            [_ c] (alts! [time-chan pause-grav quit-chan])]

        (condp = c

          pause-grav
          (let [[_ c] (alts! [resume-grav quit-chan])]
            (if (= c resume-grav)
              (recur)))

          time-chan
          (do
            (apply-gravity!)
            (recur))

          nil)))))


;;------------------------------------------------------------
;; Input-driven STATE CHANGES
;;------------------------------------------------------------

(defn try-move!
  "Try moving the current piece to the given offset."
  [dx dy]
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        nx (+ dx x)
        ny (+ dy y)]
    (if (piece-fits? piece nx ny board)
      (swap! state assoc :position [nx ny]))))

(defn try-rotate!
  "Try rotating the current piece."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        new-piece (rotate-piece piece)]
    (if (piece-fits? new-piece x y board)
      (swap! state assoc :piece new-piece))))

(defn hard-drop!
  "Hard drop the current piece."
  []
  (let [[x y] (:position @state)
        piece (:piece @state)
        board (:board @state)
        ny (get-drop-pos piece x y board)]
    (swap! state assoc :position [x ny])
    (lock-piece!)))

(defn add-key-events
  "Add all the key inputs."
  []
  (let [down-chan (chan)
        key-names {37 :left
                   38 :up
                   39 :right
                   40 :down
                   32 :space
                   16 :shift

                   49 :one
                   50 :two
                   51 :three
                   52 :four
                   53 :five
                   54 :six
                   55 :seven
                   56 :eight
                   57 :nine
                   48 :zero}
        key-name #(-> % .-keyCode key-names)
        key-down (fn [e]
                   (case (key-name e)
                     :one (do
                            (swap! state assoc :theme 0) 
                            (.preventDefault e)
                            (.html ($ "#theme") "1984")
                            (.html ($ "#theme-details") "Electronika 60"))
                     :two (do 
                            (swap! state assoc :theme 1) 
                            (.preventDefault e)
                            (.html ($ "#theme") "1986")
                            (.html ($ "#theme-details") "MS DOS"))
                     :three (do 
                              (swap! state assoc :theme 2) 
                              (.preventDefault e)
                              (.html ($ "#theme") "1986")
                              (.html ($ "#theme-details") "Tengen/Atari Arcade"))
                     :four (do 
                             (swap! state assoc :theme 3) 
                             (.preventDefault e)
                             (.html ($ "#theme") "1989")
                             (.html ($ "#theme-details") "Gameboy"))
                     :five (do 
                             (swap! state assoc :theme 4) 
                             (.preventDefault e)
                             (.html ($ "#theme") "1989")
                             (.html ($ "#theme-details") "NES"))
                     :six (do 
                            (swap! state assoc :theme 5) 
                            (.preventDefault e)
                            (.html ($ "#theme") "1989")
                            (.html ($ "#theme-details") "Sega Genesis"))
                     :seven (do 
                              (swap! state assoc :theme 6) 
                              (.preventDefault e)
                              (.html ($ "#theme") "2000")
                              (.html ($ "#theme-details") "TI-83"))
                     ;; TODO: Reorder themes here
                     :eight (do 
                              (swap! state assoc :theme 7) 
                              (.preventDefault e)
                              (.html ($ "#theme") "1998")
                              (.html ($ "#theme-details") "Gameboy color"))
                     :nine (do 
                             (swap! state assoc :theme 8) 
                             (.preventDefault e)
                             (.html ($ "#theme") "")
                             (.html ($ "#theme-details") "this might be made up."))
                     :zero (do 
                             (swap! state assoc :theme 9) 
                             (.preventDefault e)
                             (.html ($ "#theme") "2012")
                             (.html ($ "#theme-details") "Facebook"))
                     nil)
                   (if (:piece @state)
                     (case (key-name e)
                       :down  (do (put! down-chan true) (.preventDefault e))
                       :left  (do (try-move! -1  0)     (.preventDefault e))
                       :right (do (try-move!  1  0)     (.preventDefault e))
                       :space (do (hard-drop!)          (.preventDefault e))
                       :up    (do (try-rotate!)         (.preventDefault e))
                       nil)))
        key-up (fn [e]
                 (when-not (:quit @state)
                   (case (key-name e)
                     :down  (put! down-chan false)
                     :shift (toggle-record!)
                     nil)))]

    ; Add key events
    (.addEventListener js/window "keydown" key-down)
    (.addEventListener js/window "keyup" key-up)

    ; Listen to the down key, but ignore repeats.
    (let [uc (unique down-chan)]
      (go
        (loop []
          (let [[value c] (alts! [(:quit-chan @state) uc])]
            (when (= c uc)
              (swap! state assoc :soft-drop value)

              ; force gravity to reset
              (put! pause-grav 0)
              (put! resume-grav 0)
              (recur))))))

    ; Remove key events when quitting
    (go
      (<! (:quit-chan @state))
      (.removeEventListener js/window "keydown" key-down)
      (.removeEventListener js/window "keyup" key-up))))

;;------------------------------------------------------------
;; Opponent drawing
;;------------------------------------------------------------

(defn on-opponent-update
  [{:keys [id level board theme]}]

  (create-opponent-canvas! id)

  (draw-board! id board (opponent-scale cell-size) theme)
  )

;;------------------------------------------------------------
;; Entry Point
;;------------------------------------------------------------

(defn init []

  (init-state!)

  (size-canvas! "game-canvas" empty-board cell-size rows-cutoff)
  (size-canvas! "next-canvas" (next-piece-board) cell-size)

  (try-spawn-piece!)
  (add-key-events)
  (go-go-draw!)
  (go-go-gravity!)

  (display-points!)

  (.on @socket "board-update" #(on-opponent-update (read-string %)))
  (.on @socket "board-delete" delete-opponent-canvas!)
  )

(defn cleanup []

  (swap! state assoc :quit true)
  (close! (:quit-chan @state))

  )
