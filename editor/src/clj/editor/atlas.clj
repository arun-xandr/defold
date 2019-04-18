(ns editor.atlas
  (:require [clojure.string :as str]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.image :as image]
            [editor.image-util :as image-util]
            [editor.geom :as geom]
            [editor.core :as core]
            [editor.colors :as colors]
            [editor.dialogs :as dialogs]
            [editor.handler :as handler]
            [editor.gl :as gl]
            [editor.gl.shader :as shader]
            [editor.gl.texture :as texture]
            [editor.gl.vertex :as vtx]
            [editor.defold-project :as project]
            [editor.math :as math]
            [editor.properties :as properties]
            [editor.types :as types]
            [editor.workspace :as workspace]
            [editor.resource :as resource]
            [editor.resource-io :as resource-io]
            [editor.resource-node :as resource-node]
            [editor.pipeline :as pipeline]
            [editor.pipeline.texture-set-gen :as texture-set-gen]
            [editor.pipeline.tex-gen :as tex-gen]
            [editor.scene-picking :as scene-picking]
            [editor.texture-set :as texture-set]
            [editor.outline :as outline]
            [editor.validation :as validation]
            [editor.gl.pass :as pass]
            [editor.graph-util :as gu]
            [schema.core :as s])
  (:import [com.dynamo.atlas.proto AtlasProto$Atlas]
           [com.dynamo.textureset.proto TextureSetProto$TextureSet]
           [com.dynamo.tile.proto Tile$Playback]
           [editor.types Animation Image AABB]
           [java.awt.image BufferedImage]
           [com.jogamp.opengl GL GL2]
           [javax.vecmath Point3d]))

(set! *warn-on-reflection* true)

(def ^:const atlas-icon "icons/32/Icons_13-Atlas.png")
(def ^:const animation-icon "icons/32/Icons_24-AT-Animation.png")
(def ^:const image-icon "icons/32/Icons_25-AT-Image.png")

(g/deftype ^:private NameCounts {s/Str s/Int})

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0))

(shader/defshader pos-uv-vert
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (varying vec2 var_texcoord0)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)))

(shader/defshader pos-uv-frag
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture_sampler)
  (defn void main []
    (setq gl_FragColor (texture2D texture_sampler var_texcoord0.xy))))

; TODO - macro of this
(def atlas-shader (shader/make-shader ::atlas-shader pos-uv-vert pos-uv-frag))

(defn- render-rect
  [^GL2 gl rect color]
  (let [x0 (:x rect)
        y0 (:y rect)
        x1 (+ x0 (:width rect))
        y1 (+ y0 (:height rect))
        [cr cg cb ca] color]
    (.glColor4d gl cr cg cb ca)
    (.glBegin gl GL2/GL_QUADS)
    (.glVertex3d gl x0 y0 0)
    (.glVertex3d gl x0 y1 0)
    (.glVertex3d gl x1 y1 0)
    (.glVertex3d gl x1 y0 0)
    (.glEnd gl)))

(defn render-image-outline
  [^GL2 gl render-args renderables]
  (doseq [renderable renderables]
    (render-rect gl (-> renderable :user-data :rect) (if (:selected renderable) colors/selected-outline-color colors/outline-color)))
  (doseq [renderable renderables]
    (when (= (-> renderable :updatable :state :frame) (-> renderable :user-data :order))
      (render-rect gl (-> renderable :user-data :rect) colors/defold-pink))))

(defn- render-image-outlines
  [^GL2 gl render-args renderables n]
  (condp = (:pass render-args)
    pass/outline
    (render-image-outline gl render-args renderables)))

(defn- render-image-selection
  [^GL2 gl render-args renderables n]
  (assert (= (:pass render-args) pass/selection))
  (assert (= n 1))
  (let [renderable (first renderables)
        picking-id (:picking-id renderable)
        id-color (scene-picking/picking-id->color picking-id)]
    (render-rect gl (-> renderable :user-data :rect) id-color)))

(g/defnk produce-image-scene
  [_node-id image-resource order image-path->rect animation-updatable]
  (let [path (resource/proj-path image-resource)
        rect (get image-path->rect path)
        aabb (geom/rect->aabb rect)]
    {:node-id _node-id
     :aabb aabb
     :renderable {:render-fn render-image-outlines
                  :tags #{:atlas :outline}
                  :batch-key ::atlas-image
                  :user-data {:rect rect
                              :order order}
                  :passes [pass/outline]}
     :children [{:aabb aabb
                 :node-id _node-id
                 :renderable {:render-fn render-image-selection
                              :tags #{:atlas}
                              :user-data {:rect rect}
                              :passes [pass/selection]}}]
     :updatable animation-updatable}))

(defn- path->id [path]
  (-> path
      (str/split #"/")
      last
      (str/split #"\.(?=[^\.]+$)")
      first))

(defn image->animation [image id]
  (types/map->Animation {:id              id
                         :images          [image]
                         :fps             30
                         :flip-horizontal false
                         :flip-vertical   false
                         :playback        :playback-once-forward}))

(defn- unique-id-error [node-id id id-counts]
  (or (validation/prop-error :fatal node-id :id validation/prop-empty? id "Id")
      (validation/prop-error :fatal node-id :id (partial validation/prop-id-duplicate? id-counts) id)))

(defn- validate-image-id [node-id id id-counts]
  (when (and (some? id) (some? id-counts))
    (unique-id-error node-id id id-counts)))

(defn- validate-image-resource [node-id image-resource]
  (validation/prop-error :fatal node-id :image validation/prop-resource-missing? image-resource "Image"))

(g/defnode AtlasImage
  (inherits outline/OutlineNode)

  (property id g/Str
            (value (g/fnk [maybe-image-resource] (some-> maybe-image-resource resource/proj-path path->id)))
            (dynamic read-only? (g/constantly true))
            (dynamic error (g/fnk [_node-id id id-counts] (validate-image-id _node-id id id-counts))))

  (property size types/Vec2
            (value (g/fnk [maybe-image-size] [(:width maybe-image-size 0) (:height maybe-image-size 0)]))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))

  (property image resource/Resource
            (value (gu/passthrough maybe-image-resource))
            (set (fn [evaluation-context self old-value new-value]
                   (project/resource-setter evaluation-context self old-value new-value
                                            [:resource :maybe-image-resource]
                                            [:size :maybe-image-size])))
            (dynamic error (g/fnk [_node-id maybe-image-resource]
                             (validate-image-resource _node-id maybe-image-resource)))
            (dynamic edit-type (g/constantly
                                 {:type resource/Resource
                                  :ext image/exts})))

  (input id-counts NameCounts)
  (input maybe-image-resource resource/Resource)
  (output image-resource resource/Resource (g/fnk [_node-id maybe-image-resource maybe-image-size]
                                             ;; Depending on maybe-image-size provides ErrorValues from the image/ImageNode,
                                             ;; but we also want to guard against a non-assigned Image here.
                                             (or (validation/prop-error :fatal _node-id :image validation/prop-nil? maybe-image-resource "Image")
                                                 maybe-image-resource)))

  (input maybe-image-size g/Any)
  (input image-path->rect g/Any)

  (input child->order g/Any)
  (output order g/Any (g/fnk [_node-id child->order]
                        (child->order _node-id)))

  (input animation-updatable g/Any)

  (output atlas-image Image (g/fnk [image-resource maybe-image-size]
                              (Image. (resource/proj-path image-resource) nil (:width maybe-image-size) (:height maybe-image-size))))
  (output animation Animation (g/fnk [atlas-image id]
                                (image->animation atlas-image id)))
  (output node-outline outline/OutlineData :cached (g/fnk [_node-id build-errors id maybe-image-resource order]
                                                     (let [label (or id "<No Image>")]
                                                       (cond-> {:node-id _node-id
                                                                :node-outline-key label
                                                                :label label
                                                                :order order
                                                                :icon image-icon
                                                                :outline-error? (g/error-fatal? build-errors)}

                                                               (resource/openable-resource? maybe-image-resource)
                                                               (assoc :link maybe-image-resource :outline-show-link? true)))))
  (output ddf-message g/Any (g/fnk [maybe-image-resource order]
                              {:image (resource/resource->proj-path maybe-image-resource) :order order}))
  (output scene g/Any :cached produce-image-scene)
  (output build-errors g/Any :cached (g/fnk [_node-id id id-counts maybe-image-resource]
                                       (g/package-errors _node-id
                                                         (validate-image-resource _node-id maybe-image-resource)
                                                         (validate-image-id _node-id id id-counts)))))

(defn- sort-by-and-strip-order [images]
  (->> images
       (sort-by :order)
       (map #(dissoc % :order))))

(g/defnk produce-anim-ddf [id fps flip-horizontal flip-vertical playback img-ddf]
  {:id id
   :fps fps
   :flip-horizontal flip-horizontal
   :flip-vertical flip-vertical
   :playback playback
   :images (sort-by-and-strip-order img-ddf)})

(defn- attach-image-to-atlas [atlas-node image-node]
  (concat
    (g/connect image-node :_node-id         atlas-node :nodes)
    (g/connect image-node :animation        atlas-node :animations)
    (g/connect image-node :atlas-image      atlas-node :atlas-images)
    (g/connect image-node :build-errors     atlas-node :child-build-errors)
    (g/connect image-node :ddf-message      atlas-node :img-ddf)
    (g/connect image-node :id               atlas-node :animation-ids)
    (g/connect image-node :image-resource   atlas-node :image-resources)
    (g/connect image-node :node-outline     atlas-node :child-outlines)
    (g/connect image-node :scene            atlas-node :child-scenes)
    (g/connect atlas-node :child->order     image-node :child->order)
    (g/connect atlas-node :id-counts        image-node :id-counts)
    (g/connect atlas-node :image-path->rect image-node :image-path->rect)
    (g/connect atlas-node :updatable        image-node :animation-updatable)))

(defn- attach-image-to-animation [animation-node image-node]
  (concat
    (g/connect image-node     :_node-id         animation-node :nodes)
    (g/connect image-node     :atlas-image      animation-node :atlas-images)
    (g/connect image-node     :build-errors     animation-node :child-build-errors)
    (g/connect image-node     :ddf-message      animation-node :img-ddf)
    (g/connect image-node     :image-resource   animation-node :image-resources)
    (g/connect image-node     :node-outline     animation-node :child-outlines)
    (g/connect image-node     :scene            animation-node :child-scenes)
    (g/connect animation-node :child->order     image-node     :child->order)
    (g/connect animation-node :image-path->rect image-node     :image-path->rect)
    (g/connect animation-node :updatable        image-node     :animation-updatable)))

(defn- attach-animation-to-atlas [atlas-node animation-node]
  (concat
    (g/connect animation-node :_node-id         atlas-node     :nodes)
    (g/connect animation-node :animation        atlas-node     :animations)
    (g/connect animation-node :build-errors     atlas-node     :child-build-errors)
    (g/connect animation-node :ddf-message      atlas-node     :anim-ddf)
    (g/connect animation-node :id               atlas-node     :animation-ids)
    (g/connect animation-node :image-resources  atlas-node     :image-resources)
    (g/connect animation-node :node-outline     atlas-node     :child-outlines)
    (g/connect animation-node :scene            atlas-node     :child-scenes)
    (g/connect atlas-node     :anim-data        animation-node :anim-data)
    (g/connect atlas-node     :gpu-texture      animation-node :gpu-texture)
    (g/connect atlas-node     :id-counts        animation-node :id-counts)
    (g/connect atlas-node     :image-path->rect animation-node :image-path->rect)))

(defn render-animation
  [^GL2 gl render-args renderables n]
  (texture-set/render-animation-overlay gl render-args renderables n ->texture-vtx atlas-shader))

(g/defnk produce-animation-updatable
  [_node-id id anim-data]
  (texture-set/make-animation-updatable _node-id "Atlas Animation" (get anim-data id)))

(g/defnk produce-animation-scene
  [_node-id id child-scenes gpu-texture updatable anim-data]
  {:node-id    _node-id
   :aabb       geom/null-aabb
   :renderable {:render-fn render-animation
                :tags #{:atlas}
                :batch-key nil
                :user-data {:gpu-texture gpu-texture
                            :anim-id     id
                            :anim-data   (get anim-data id)}
                :passes    [pass/overlay pass/selection]}
   :updatable  updatable
   :children   child-scenes})

(defn- validate-animation-id [node-id id id-counts]
  (unique-id-error node-id id id-counts))

(defn- validate-animation-fps [node-id fps]
  (validation/prop-error :fatal node-id :fps validation/prop-negative? fps "Fps"))

(g/defnode AtlasAnimation
  (inherits core/Scope)
  (inherits outline/OutlineNode)

  (property id g/Str
            (dynamic error (g/fnk [_node-id id id-counts] (validate-animation-id _node-id id id-counts))))
  (property fps g/Int
            (default 30)
            (dynamic error (g/fnk [_node-id fps] (validate-animation-fps _node-id fps))))
  (property flip-horizontal g/Bool)
  (property flip-vertical   g/Bool)
  (property playback        types/AnimationPlayback
            (dynamic edit-type (g/constantly (properties/->pb-choicebox Tile$Playback))))

  (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

  (input atlas-images Image :array)
  (input img-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input child-build-errors g/Any :array)
  (input id-counts NameCounts)
  (input anim-data g/Any)

  (input image-resources g/Any :array)
  (output image-resources g/Any (gu/passthrough image-resources))

  (input image-path->rect g/Any)
  (output image-path->rect g/Any (gu/passthrough image-path->rect))

  (input gpu-texture g/Any)

  (output animation Animation (g/fnk [id atlas-images fps flip-horizontal flip-vertical playback]
                                      (types/->Animation id atlas-images fps flip-horizontal flip-vertical playback)))

  (output node-outline outline/OutlineData :cached
          (g/fnk [_node-id child-outlines id own-build-errors]
            {:node-id _node-id
             :node-outline-key id
             :label id
             :children (sort-by :order child-outlines)
             :icon animation-icon
             :outline-error? (g/error-fatal? own-build-errors)
             :child-reqs [{:node-type AtlasImage
                           :tx-attach-fn attach-image-to-animation}]}))
  (output ddf-message g/Any :cached produce-anim-ddf)
  (output updatable g/Any :cached produce-animation-updatable)
  (output scene g/Any :cached produce-animation-scene)
  (output own-build-errors g/Any :cached (g/fnk [_node-id fps id id-counts]
                                           (g/package-errors _node-id
                                                             (validate-animation-id _node-id id id-counts)
                                                             (validate-animation-fps _node-id fps))))
  (output build-errors g/Any :cached (g/fnk [_node-id child-build-errors own-build-errors]
                                       (g/package-errors _node-id
                                                         child-build-errors
                                                         own-build-errors))))

(g/defnk produce-save-value [margin inner-padding extrude-borders img-ddf anim-ddf]
  {:margin margin
   :inner-padding inner-padding
   :extrude-borders extrude-borders
   :images (sort-by-and-strip-order img-ddf)
   :animations anim-ddf})

(defn- validate-margin [node-id margin]
  (validation/prop-error :fatal node-id :margin validation/prop-negative? margin "Margin"))

(defn- validate-inner-padding [node-id inner-padding]
  (validation/prop-error :fatal node-id :inner-padding validation/prop-negative? inner-padding "Inner Padding"))

(defn- validate-extrude-borders [node-id extrude-borders]
  (validation/prop-error :fatal node-id :extrude-borders validation/prop-negative? extrude-borders "Extrude Borders"))

(g/defnk produce-build-targets [_node-id resource texture-set packed-image-generator texture-profile build-settings build-errors]
  (g/precluding-errors build-errors
    (let [project           (project/get-project _node-id)
          workspace         (project/workspace project)
          compress?         (:compress-textures? build-settings false)
          texture-target    (image/make-texture-build-target workspace _node-id packed-image-generator texture-profile compress?)
          pb-msg            texture-set
          dep-build-targets [texture-target]]
      [(pipeline/make-protobuf-build-target resource dep-build-targets
                                            TextureSetProto$TextureSet
                                            (assoc pb-msg :texture (-> texture-target :resource :resource))
                                            [:texture])])))

(defn gen-renderable-vertex-buffer
  [width height]
  (let [x0 0
        y0 0
        x1 width
        y1 height]
    (persistent!
      (doto (->texture-vtx 6)
           (conj! [x0 y0 0 1 0 0])
           (conj! [x0 y1 0 1 0 1])
           (conj! [x1 y1 0 1 1 1])

           (conj! [x1 y1 0 1 1 1])
           (conj! [x1 y0 0 1 1 0])
           (conj! [x0 y0 0 1 0 0])))))

(defn- render-atlas
  [^GL2 gl render-args [renderable] n]
  (let [{:keys [pass]} render-args]
    (condp = pass
      pass/transparent
      (let [{:keys [user-data]} renderable
            {:keys [vbuf gpu-texture]} user-data
            vertex-binding (vtx/use-with ::atlas-binding vbuf atlas-shader)]
        (gl/with-gl-bindings gl render-args [gpu-texture atlas-shader vertex-binding]
          (shader/set-uniform atlas-shader gl "texture_sampler" 0)
          (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 6))))))

(defn- render-atlas-outline
  [^GL2 gl render-args [renderable] n]
  (let [{:keys [pass]} render-args]
    (condp = pass
      pass/outline
      (let [{:keys [aabb]} renderable
            [x0 y0] (math/vecmath->clj (types/min-p aabb))
            [x1 y1] (math/vecmath->clj (types/max-p aabb))
            [cr cg cb ca] colors/outline-color]
        (.glColor4d gl cr cg cb ca)
        (.glBegin gl GL2/GL_QUADS)
        (.glVertex3d gl x0 y0 0)
        (.glVertex3d gl x0 y1 0)
        (.glVertex3d gl x1 y1 0)
        (.glVertex3d gl x1 y0 0)
        (.glEnd gl)))))

(g/defnk produce-scene
  [_node-id aabb layout-size gpu-texture child-scenes texture-profile]
  (let [[width height] layout-size]
    {:aabb aabb
     :info-text (format "%d x %d (%s profile)" width height (:name texture-profile))
     :renderable {:render-fn render-atlas
                  :user-data {:gpu-texture gpu-texture
                              :vbuf        (gen-renderable-vertex-buffer width height)}
                  :tags #{:atlas}
                  :passes [pass/transparent]}
     :children (into [{:aabb aabb
                       :renderable {:render-fn render-atlas-outline
                                    :tags #{:atlas :outline}
                                    :passes [pass/outline]}}]
                     child-scenes)}))

(defn- generate-texture-set-data [{:keys [animations all-atlas-images margin inner-padding extrude-borders]}]
  (texture-set-gen/atlas->texture-set-data animations all-atlas-images margin inner-padding extrude-borders))

(defn- call-generator [generator]
  ((:f generator) (:args generator)))

(defn- generate-packed-image [{:keys [_node-id image-resources texture-set-data-generator]}]
  (let [buffered-images (mapv #(resource-io/with-error-translation % _node-id nil
                                 (image-util/read-image %))
                              image-resources)
        errors (filter g/error? buffered-images)]
    (if (seq errors)
      (g/error-aggregate errors)
      (let [id->image (zipmap (map resource/proj-path image-resources) buffered-images)]
        (texture-set-gen/layout-images (:layout (call-generator texture-set-data-generator)) id->image)))))

(g/defnk produce-anim-data
  [texture-set uv-transforms]
  (texture-set/make-anim-data texture-set uv-transforms))

(g/defnk produce-image-path->rect
  [layout-size layout-rects]
  (let [[w h] layout-size]
    (into {} (map (fn [{:keys [path x y width height]}]
                    [path (types/->Rect path x (- h height y) width height)]))
          layout-rects)))

(defn- atlas-outline-sort-by-fn [v]
  [(:name (g/node-type* (:node-id v)))])

(g/defnode AtlasNode
  (inherits resource-node/ResourceNode)

  (property size types/Vec2
            (value (g/fnk [layout-size] layout-size))
            (dynamic edit-type (g/constantly {:type types/Vec2 :labels ["W" "H"]}))
            (dynamic read-only? (g/constantly true)))
  (property margin g/Int
            (default 0)
            (dynamic error (g/fnk [_node-id margin] (validate-margin _node-id margin))))
  (property inner-padding g/Int
            (default 0)
            (dynamic error (g/fnk [_node-id inner-padding] (validate-inner-padding _node-id inner-padding))))
  (property extrude-borders g/Int
            (default 0)
            (dynamic error (g/fnk [_node-id extrude-borders] (validate-extrude-borders _node-id extrude-borders))))

  (output child->order g/Any :cached (g/fnk [nodes] (zipmap nodes (range))))

  (input build-settings g/Any)
  (input texture-profiles g/Any)
  (input atlas-images g/Any :array)
  (input animations Animation :array)
  (input animation-ids g/Str :array)
  (input img-ddf g/Any :array)
  (input anim-ddf g/Any :array)
  (input child-scenes g/Any :array)
  (input child-build-errors g/Any :array)
  (input image-resources g/Any :array)

  (output texture-profile g/Any (g/fnk [texture-profiles resource]
                                  (tex-gen/match-texture-profile texture-profiles (resource/proj-path resource))))

  (output all-atlas-images           [Image]             :cached (g/fnk [animations]
                                                                   (into [] (comp (mapcat :images) (distinct)) animations)))

  (output texture-set-data-generator g/Any (g/fnk [_node-id animations all-atlas-images margin inner-padding extrude-borders :as args]
                                                  (or (when-let [errors (->> [[margin "Margin"]
                                                                              [inner-padding "Inner Padding"]
                                                                              [extrude-borders "Extrude Borders"]]
                                                                             (keep (fn [[v name]]
                                                                                     (validation/prop-error :fatal _node-id :layout-result validation/prop-negative? v name)))
                                                                             not-empty)]
                                                        (g/error-aggregate errors))
                                                      {:f    generate-texture-set-data
                                                       :args args})))

  (output texture-set-data g/Any               :cached (g/fnk [texture-set-data-generator] (call-generator texture-set-data-generator)))
  (output layout-size      g/Any               (g/fnk [texture-set-data] (:size texture-set-data)))
  (output texture-set      g/Any               (g/fnk [texture-set-data] (:texture-set texture-set-data)))
  (output uv-transforms    g/Any               (g/fnk [texture-set-data] (:uv-transforms texture-set-data)))
  (output layout-rects     g/Any               (g/fnk [texture-set-data] (:rects texture-set-data)))

  (output packed-image-generator g/Any (g/fnk [_node-id texture-set-data-generator image-resources]
                                              (let [flat-image-resources (filterv some? (flatten image-resources))
                                                    shas                 (map #(resource-io/with-error-translation % _node-id nil
                                                                                 (resource/resource->sha1-hex %))
                                                                              flat-image-resources)
                                                    errors               (filter g/error? shas)]
                                                (if (seq errors)
                                                  (g/error-aggregate errors)
                                                  {:f    generate-packed-image
                                                   :sha1 (str/join shas)
                                                   :args {:_node-id                   _node-id
                                                          :image-resources            flat-image-resources
                                                          :texture-set-data-generator texture-set-data-generator}}))))

  (output packed-image     BufferedImage       :cached (g/fnk [packed-image-generator] (call-generator packed-image-generator)))

  (output texture-image    g/Any               (g/fnk [packed-image texture-profile]
                                                 (tex-gen/make-preview-texture-image packed-image texture-profile)))

  (output aabb             AABB                (g/fnk [layout-size]
                                                 (if (= [0 0] layout-size)
                                                   geom/null-aabb
                                                   (let [[w h] layout-size]
                                                     (types/->AABB (Point3d. 0 0 0) (Point3d. w h 0))))))

  (output gpu-texture      g/Any               :cached (g/fnk [_node-id texture-image]
                                                         (texture/texture-image->gpu-texture _node-id
                                                                                             texture-image
                                                                                             {:min-filter gl/nearest
                                                                                              :mag-filter gl/nearest})))

  (output anim-data        g/Any               :cached produce-anim-data)
  (output image-path->rect g/Any               :cached produce-image-path->rect)
  (output anim-ids         g/Any               :cached (g/fnk [animation-ids] (filter some? animation-ids)))
  (output id-counts        NameCounts          :cached (g/fnk [anim-ids] (frequencies anim-ids)))
  (output node-outline     outline/OutlineData :cached (g/fnk [_node-id child-outlines own-build-errors]
                                                         {:node-id          _node-id
                                                          :node-outline-key "Atlas"
                                                          :label            "Atlas"
                                                          :children         (vec (sort-by atlas-outline-sort-by-fn child-outlines))
                                                          :icon             atlas-icon
                                                          :outline-error?   (g/error-fatal? own-build-errors)
                                                          :child-reqs       [{:node-type    AtlasImage
                                                                              :tx-attach-fn attach-image-to-atlas}
                                                                             {:node-type    AtlasAnimation
                                                                              :tx-attach-fn attach-animation-to-atlas}]}))
  (output save-value       g/Any          :cached produce-save-value)
  (output build-errors     g/Any          :cached produce-build-errors)
  (output build-targets    g/Any          :cached produce-build-targets)
  (output updatable        g/Any          (g/fnk [] nil))
  (output scene            g/Any          :cached produce-scene)
  (output own-build-errors g/Any          :cached (g/fnk [_node-id extrude-borders inner-padding margin]
                                                    (g/package-errors _node-id
                                                                      (validate-margin _node-id margin)
                                                                      (validate-inner-padding _node-id inner-padding)
                                                                      (validate-extrude-borders _node-id extrude-borders))))
  (output build-errors     g/Any          :cached (g/fnk [_node-id child-build-errors own-build-errors]
                                                    (g/package-errors _node-id
                                                                      child-build-errors
                                                                      own-build-errors))))

(defn- make-image-nodes
  [attach-fn parent image-resources]
  (let [graph-id (g/node-id->graph-id parent)]
    (for [image-resource image-resources]
      (g/make-nodes
        graph-id
        [atlas-image [AtlasImage {:image image-resource}]]
        (attach-fn parent atlas-image)))))

(def ^:private make-image-nodes-in-atlas (partial make-image-nodes attach-image-to-atlas))
(def ^:private make-image-nodes-in-animation (partial make-image-nodes attach-image-to-animation))

(defn add-images [atlas-node img-resources]
  ; used by tests
  (make-image-nodes-in-atlas atlas-node img-resources))

(defn- make-atlas-animation [atlas-node anim]
  (let [graph-id (g/node-id->graph-id atlas-node)
        project (project/get-project atlas-node)
        workspace (project/workspace project)
        image-resources (mapv (comp (partial workspace/resolve-workspace-resource workspace) :image) (:images anim))]
    (g/make-nodes
      graph-id
      [atlas-anim [AtlasAnimation :flip-horizontal (:flip-horizontal anim) :flip-vertical (:flip-vertical anim)
                   :fps (:fps anim) :playback (:playback anim) :id (:id anim)]]
      (attach-animation-to-atlas atlas-node atlas-anim)
      (make-image-nodes-in-animation atlas-anim image-resources))))

(defn- update-int->bool [keys m]
  (reduce (fn [m key]
            (if (contains? m key)
              (update m key (complement zero?))
              m))
            m
            keys))

(defn load-atlas [project self resource atlas]
  (let [workspace (project/workspace project)
        image-resources (into []
                              (comp (map :image)
                                    (remove empty?)
                                    (distinct)
                                    (map (partial workspace/resolve-workspace-resource workspace)))
                              (:images atlas))]
    (concat
      (g/connect project :build-settings self :build-settings)
      (g/connect project :texture-profiles self :texture-profiles)
      (g/set-property self :margin (:margin atlas))
      (g/set-property self :inner-padding (:inner-padding atlas))
      (g/set-property self :extrude-borders (:extrude-borders atlas))
      (make-image-nodes-in-atlas self image-resources)
      (map (comp (partial make-atlas-animation self)
                 (partial update-int->bool [:flip-horizontal :flip-vertical]))
           (:animations atlas)))))

(defn register-resource-types [workspace]
  (resource-node/register-ddf-resource-type workspace
                                    :ext "atlas"
                                    :label "Atlas"
                                    :build-ext "texturesetc"
                                    :node-type AtlasNode
                                    :ddf-type AtlasProto$Atlas
                                    :load-fn load-atlas
                                    :icon atlas-icon
                                    :view-types [:scene :text]
                                    :view-opts {:scene {:grid false}}))

(defn- selection->atlas [selection] (handler/adapt-single selection AtlasNode))
(defn- selection->animation [selection] (handler/adapt-single selection AtlasAnimation))
(defn- selection->image [selection] (handler/adapt-single selection AtlasImage))

(def ^:private default-animation
  {:flip-horizontal false
   :flip-vertical false
   :fps 60
   :playback :playback-loop-forward
   :id "New Animation"})

(defn- add-animation-group-handler [app-view atlas-node]
  (let [op-seq (gensym)
        [animation-node] (g/tx-nodes-added
                           (g/transact
                             (concat
                               (g/operation-sequence op-seq)
                               (g/operation-label "Add Animation Group")
                               (make-atlas-animation atlas-node default-animation))))]
    (g/transact
      (concat
        (g/operation-sequence op-seq)
        (app-view/select app-view [animation-node])))))

(handler/defhandler :add :workbench
  (label [] "Add Animation Group")
  (active? [selection] (selection->atlas selection))
  (run [app-view selection] (add-animation-group-handler app-view (selection->atlas selection))))

(defn- add-images-handler [app-view workspace project parent] ; parent = new parent of images
  (when-some [image-resources (seq (dialogs/make-resource-dialog workspace project {:ext image/exts :title "Select Images" :selection :multiple}))]
    (let [op-seq (gensym)
          image-nodes (g/tx-nodes-added
                        (g/transact
                          (concat
                            (g/operation-sequence op-seq)
                            (g/operation-label "Add Images")
                            (cond
                              (g/node-instance? AtlasNode parent)
                              (make-image-nodes-in-atlas parent image-resources)

                              (g/node-instance? AtlasAnimation parent)
                              (make-image-nodes-in-animation parent image-resources)

                              :else
                              (let [parent-node-type @(g/node-type* parent)]
                                (throw (ex-info (str "Unsupported parent type " (:name parent-node-type))
                                                {:parent-node-type parent-node-type})))))))]
      (g/transact
        (concat
          (g/operation-sequence op-seq)
          (app-view/select app-view image-nodes))))))

(handler/defhandler :add-from-file :workbench
  (label [] "Add Images...")
  (active? [selection] (or (selection->atlas selection) (selection->animation selection)))
  (run [app-view project selection] (when-some [parent-node (or (selection->atlas selection)
                                                                (selection->animation selection))]
                                      (let [workspace (project/workspace project)]
                                        (add-images-handler app-view workspace project parent-node)))))

(defn- vec-move
  [v x offset]
  (let [current-index (.indexOf ^java.util.List v x)
        new-index (max 0 (+ current-index offset))
        [before after] (split-at new-index (remove #(= x %) v))]
    (vec (concat before [x] after))))

(defn- move-node!
  [node-id offset]
  (let [parent (core/scope node-id)
        children (vec (g/node-value parent :nodes))
        new-children (vec-move children node-id offset)
        connections (keep (fn [[source source-label target target-label]]
                            (when (and (= source node-id)
                                       (= target parent))
                              [source-label target-label]))
                          (g/outputs node-id))]
    (g/transact
      (concat
        (for [child children
              [source target] connections]
          (g/disconnect child source parent target))
        (for [child new-children
              [source target] connections]
          (g/connect child source parent target))))))

(defn- move-active? [selection]
  (some->> selection
    selection->image
    core/scope
    (g/node-instance? AtlasAnimation)))

(handler/defhandler :move-up :workbench
  (active? [selection] (move-active? selection))
  (run [selection] (move-node! (selection->image selection) -1)))

(handler/defhandler :move-down :workbench
  (active? [selection] (move-active? selection))
  (run [selection] (move-node! (selection->image selection) 1)))
