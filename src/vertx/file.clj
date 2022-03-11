(ns vertx.file
  (:import [io.vertx.core Vertx Context]
           [io.vertx.core.file FileSystem]))

(defn file-system [vsm]
  (cond (instance? Vertx vsm)   (.fileSystem vsm)
        (instance? FileSystem vsm)  vsm
        (instance? Context vsm)     (file-system (.owner vsm))
        :else                       (throw (RuntimeException. "Not Vertx Instance"))))

(defn- build-copy [& opt]
  opt)

(defn copy [vsm from to & opt]
  (.copy (file-system vsm) from to (build-copy opt)))

(defn move [vsm from to & opt]
  (.move (file-system vsm) from to (build-copy opt)))

(defn truncate [vsm path len]
  (.truncate (file-system vsm) path len))

(defn chmod [vsm path perm]
  (.chmod (file-system vsm) path perm))

(defn chown
  "(chown vertx path user group) or (chown vertx path user)"
  [vsm path & user-group]
  (let [[user group] user-group]
    (.chown (file-system vsm) path user group)))

(defn prop [vsm path]
  (.prop (file-system vsm) path))

(defn link [vsm link existing]
  (.link (file-system vsm) link existing))

(defn symlink [vsm link existing]
  (.symlink (file-system vsm) link existing))

(defn unlink [vsm link]
  (.unlink (file-system vsm) link))

(defn read-link [vsm link]
  (.readLink (file-system vsm) link))

(defn delete [vsm path & rec]
  (.deleteRecursive (file-system vsm) path rec))

(defn mkdir [vsm path perm]
  (.mkdir (file-system vsm) path perm))

(defn read-dir [vsm path]
  (.readDir (file-system vsm) path))

(defn read-file [vsm path]
  (.readFile (file-system vsm) path))

(defn write-file [vsm path data]
  (.writeFile (file-system vsm) path data))

(defn open [vsm path option]
  (.open (file-system vsm) path option))

(defn exists? [vsm path]
  (.exists (file-system vsm) path))

(defn create-file [vsm path]
  (.createFile (file-system vsm) path))

(defn create-tmp-file [vsm path suffer perm]
  (.ceateTmpFile (file-system vsm) path suffer perm))
