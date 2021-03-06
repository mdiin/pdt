;; ## PDF creation from templates
;;
;; pdf-stamper lets you build complete PDF documents without worrying about building the layout in code.
;; Those who have tried will know that it is by no means a simple task getting the layout
;; just right, and building a layout that can adapt to changing requirements can get frustrating in the
;; long run.
;;
;; With pdf-stamper the layout is decoupled from the code extracting and manipulating the data. This leads
;; to a simpler process for building PDF documents from your data: Data placement is controlled by
;; template description datastructures, and data is written to PDF pages defining the layout.

(ns pdf-stamper
  (:require
    [pdf-stamper.context :as context]
    [pdf-stamper.text :as text]
    [pdf-stamper.text.parsed :as parsed-text]
    [pdf-stamper.images :as images]
    [potemkin])
  (:import
    [org.apache.pdfbox.cos COSDictionary COSName]
    [org.apache.pdfbox.pdmodel PDDocument PDPage]
    [org.apache.pdfbox.pdmodel PDPageContentStream]))

;; ## Templates
;;
;; template descriptions are regular Clojure maps with the three keys:
;;
;; - `:name`
;; - `:overflow`
;; - `:holes`
;;
;; The `:overflow` key is optional. It defines which template description to use if/when a hole on
;; this template overflows. If it is not present text will be truncated.
;;
;; ### Holes
;;
;; Holes are what make a template description: They define where on the page the various pieces of data
;; are put, and how.

(defmulti fill-hole
  "There are a number of hole types built in to pdf-stamper, but new hole types can be added
  by implementing this multimethod.

  If a hole type should be able to overflow, the return value from a call to `fill-hole` must
  be a map of the form `{<hole name> {:contents ...}}`."
  (fn [document c-stream hole location-data context] (:type hole)))

;; All holes have these fields in common:
;;
;; - `:height`
;; - `:width`
;; - `:x`
;; - `:y`
;; - `:name`
;; - `:type`
;; - `:priority`
;;
;; Coordinates and widths/heights are always in PDF points (1/72 inch).
;;
;; *Note*: The PDF coordinate system starts from the bottom left, and increasing y-values move the cursor up. Thus, all `(x,y)`
;; coordinates specified in templates should be to the lower left corner.
;;
;; `:priority` is effectively a layering of the contents on template pages; e.g. if you have two overlapping holes on a template
;; the one with the lowest value in `:priority` will be drawn on the page first, and the other hole on top of that.

(defn- fill-holes
  "When filling the holes on a page we have to take into account that Clojure sequences are
  lazy by default; i.e. we cannot expect the side-effects of stamping to the PDF page to have
  happened just by applying the `map` function. `doall` is used to force all side-effects
  before returning the resulting seq of overflowing holes.

  *Note*: Holes where the page does not contain data will be skipped."
  [document c-stream holes page-data context]
  (doall
    (into {}
          (map (fn [hole]
                 (when-let [location-data (get-in page-data [:locations (:name hole)])]
                   (fill-hole document c-stream hole location-data context)))
               (sort-by :priority holes)))))

;; The types supported out of the box are:
;;
;; - `:image`
;; - `:text`
;; - `:text-parsed`
;;
;; For specifics on the hole types supported out of the box, see the documentation for their respective namespaces.

(defmethod fill-hole :image
  [document c-stream hole location-data context]
  (let [data (merge hole location-data)]
    (images/fill-image document
                       c-stream
                       data
                       context)))

(defmethod fill-hole :text-parsed
  [document c-stream hole location-data context]
  (let [data (update-in (merge hole location-data)
                        [:contents :text]
                        #(if (string? %) (parsed-text/get-paragraph-nodes %) %))]
    (text/fill-text-parsed document
                           c-stream
                           data
                           context)))

(defmethod fill-hole :text
  [document c-stream hole location-data context]
  (let [data (merge hole location-data)]
    (text/fill-text document
                    c-stream
                    data
                    context)))

;; ## The Context
;;
;; The context is the datastructure that contains additional data needed by pdf-stamper. For now that is fonts and templates (both descriptions and files).
;; This namespace contains referrals to the four important user-facing functions from the context namespace, namely `add-template`, `add-template-partial`,
;; `add-font`, and `base-context`. For a detailed write-up on the context, please refer to the namespace documentation.

(potemkin/import-vars
  [pdf-stamper.context
   add-template
   add-template-partial
   add-font
   base-context])

;; ## Filling pages
;;
;; pdf-stamper exists to fill data onto pages while following a pre-defined layout. This is where the magic happens.

(defn- skip-page?
  "The page should be skipped if the template specifies that it can only be printed
  on a specific page, but supplies no filler template."
  [template next-page-number]
  (let [{:keys [pages filler]} (:only-on template)]
    (cond
      (and (odd? next-page-number)
           (= pages :even))
      (not filler)

      (and (even? next-page-number)
           (= pages :odd))
      (not filler)

      (and (fn? pages)
           (not (pages next-page-number)))
      (not filler)

      :default
      false)))

(defn- insert-before
  "Returns a template to use for a filler page if appropriate, nil otherwise."
  [template next-page-number]
  (let [{:keys [pages filler]} (:only-on template)]
    (cond
      (and (odd? next-page-number)
           (= pages :even))
      [filler]

      (and (even? next-page-number)
           (= pages :odd))
      [filler]

      (and (fn? pages)
           (not (pages next-page-number)))
      (repeat (count
                (sequence
                  (comp
                    (map pages)
                    (take-while not))
                  (iterate inc next-page-number)))
              filler)

      :default
      nil)))

(defn- transform-page-with
  "Returns a map of transforms to apply on the page, or nil if none apply."
  [template number-of-pages]
  (if (even? number-of-pages)
    (get-in template [:transform-pages :even])
    (get-in template [:transform-pages :odd])))

(defmulti transform
  "Apply transformation to a page. Transform is a vector `[:transform-name args]`"
  (fn [page transform]
    (first transform)))

(defmethod transform :rotate
  [page transform]
  (let [[_ degrees] transform]
    (doto page
      (.setRotation degrees))))

;; Trying to stamp a page that requests a template not in the context is an error. To make the precondition of `fill-page`
;; easier to read we name it.

(defn- page-template-exists?
  [page-data context]
  (get-in context [:templates (:template page-data)]))

(defn- fill-page
  "Every single page is passed through this function, which extracts
  the relevant template and description for the page data, adds it to
  the document being built, and delegates the actual work to the hole-
  filling functions defined above.

  The template to use is extracted from the page data. Using this the
  available holes, template PDF page, and template to use with overflowing
  holes (if any) are extracted from the context.

  Any overflowing holes are handled by calling recursively with the
  overflow. All other holes are copied as-is to the new page, to make
  repeating holes possible.

  *Future*: It would probably be wise to find a better way than a direct
  recursive call to handle overflows. Otherwise handling large bodies of
  text could become a problem."
  [document page-data context]
  (assert (page-template-exists? page-data context)
          (str "No template " (:template page-data) " for page."))
  (let [template (context/template (:template page-data) context)]
    (if (skip-page? template (inc (.getNumberOfPages document)))
      []
      (let [fill-page-vec (reduce (fn [acc filler]
                                    (let [filler-data {:template filler
                                                       :locations (:filler-locations page-data)}]
                                      (into acc (fill-page document filler-data context))))
                                  []
                                  (insert-before template (inc (.getNumberOfPages document))))
            template-overflow (:overflow template)
            template-transforms (:transform-pages template)
            template-holes (if (odd? (inc (.getNumberOfPages document)))
                             (context/template-holes-odd (:template page-data) context)
                             (context/template-holes-even (:template page-data) context))
            template-doc (if (odd? (inc (.getNumberOfPages document)))
                           (context/template-document-odd (:template page-data) context)
                           (context/template-document-even (:template page-data) context))
            template-page (-> template-doc (.getPage 0))]
        (.importPage document template-page)
        (let [inserted-page (.getPage document (dec (.getNumberOfPages document)))
              template-c-stream (PDPageContentStream. document inserted-page true false)
              overflows (fill-holes document template-c-stream (sort-by :priority template-holes) page-data context)
              overflow-page-data {:template template-overflow
                                  :locations (when (seq overflows)
                                               (merge (:locations page-data) overflows))}]
          (when-let [transforms (transform-page-with template (.getNumberOfPages document))]
            (doseq [t transforms]
              (transform inserted-page t)))
          (.close template-c-stream)
          (concat fill-page-vec
                  (if (and (seq (:locations overflow-page-data))
                           (:template overflow-page-data))
                    (conj (fill-page document overflow-page-data context) template-doc)
                    [template-doc])))))))

(defn fill-pages
  "When the context is populated with fonts and templates, this is the
  function to call. The data passed in as the first argument is a description
  of each individual page, i.e. a seq of maps containing the keys:

  - `:template`
  - `:locations`

  The former is the name of a template in the context, and the latter is a
  map where the keys are hole names present in the template. The value is
  always a map with the key `:contents`, which itself is a map. The key
  in the contents map depends on the type of the hole, as defined in the
  template; e.g. `:image` for image holes, `:text` for text and parsed text
  holes. This is really an implementation detail of the individual functions
  for filling the holes.

  A map of options can be passed to this function:

  - `number-of-pages`:
    - If `number?` forces document to be at least of that specific size by prepending enough
      blank pages to the last page.
    - If `fn?` expects `number-of-pages` to be a fn accepting the current page count
      and returning the number of blank pages to prepend to the last page.

  The completed document is written to the `out` argument, or to a
  `java.io.ByteArrayOutputStream` if no `out` is supplied."
  ([pages context options]
   (fill-pages nil pages context options))
  ([out pages context options]
   (let [output (or out (java.io.ByteArrayOutputStream.))]
     (with-open [document (PDDocument.)]
       (let [context-with-embedded-fonts (reduce (fn [context [font style]]
                                                   (context/embed-font document font style context))
                                                 context
                                                 (:fonts-to-embed context))
             open-documents (doall (map #(fill-page document % context-with-embedded-fonts) pages))]
         (.save document output)
         (doseq [doc (flatten open-documents)]
           (.close doc))))
     output)))

;; This concludes the discussion of the primary interface to pdf-stamper. Following are the namespace documentations for the functionality
;; that is not directly user-facing.

