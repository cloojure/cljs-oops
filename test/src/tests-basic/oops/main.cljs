(ns oops.main
  (:require [cljs.test :refer-macros [deftest testing is are run-tests use-fixtures]]
            [clojure.string :as string]
            [oops.core :refer [oget oset! ocall! oapply! ocall oapply
                               oget+ oset!+ ocall!+ oapply!+ ocall+ oapply+]]
            [oops.config :refer [with-runtime-config with-child-factory]]
            [oops.schema :as schema]
            [oops.tools
             :refer [with-captured-console]
             :refer-macros [init-test!
                            runonce
                            when-advanced-mode when-not-advanced-mode
                            under-phantom
                            under-chrome
                            if-phantom
                            with-console-recording
                            when-compiler-config when-not-compiler-config]]))

(runonce
  (init-test!))

(use-fixtures :once with-captured-console)

(deftest test-oget
  (let [sample-obj #js {:key               "val"
                        "@#$%fancy key^&*" "fancy-val"
                        "nested"           #js {:nested-key1  "nk1"
                                                "nested-key2" 2}}
        make-selector-dynamically (fn [path] path)]
    (testing "simple static get"
      (are [key expected] (= (oget sample-obj key) expected)
        "non-existent" nil
        "key" "val"
        "@#$%fancy key^&*" "fancy-val"
        ["nested" "nested-key2"] 2))
    (testing "simple dynamic get"
      (is (= (oget+ sample-obj (make-selector-dynamically "key")) "val"))
      (is (= (oget+ sample-obj (make-selector-dynamically "xxx")) nil))
      (is (= (oget+ sample-obj (make-selector-dynamically "nested") "nested-key1") "nk1"))
      (is (= (oget+ sample-obj (make-selector-dynamically ["nested" "nested-key1"])) "nk1"))
      (is (= (oget+ sample-obj [(make-selector-dynamically "nested") "nested-key1"]) "nk1")))
    (testing "static soft get"
      (are [selector expected] (= (oget sample-obj selector) expected)
        ".?key" "val"
        ".?x.?y" nil
        "?a" nil
        "?nested.nested-key1" "nk1"
        "?nested.?missing.?xxx" nil))
    (testing "dynamic soft get"
      (are [selector expected] (= (oget+ sample-obj selector) expected)
        (identity ".?key") "val"
        (identity ".?x.?y") nil
        (identity "?a") nil
        (identity "?nested.nested-key1") "nk1"
        (identity "?nested.?missing.?xxx") nil))
    (when-not-advanced-mode
      (testing "invalid selectors"
        (are [input] (thrown-with-msg? js/Error #"Invalid selector" (oget+ sample-obj (make-selector-dynamically input)))
          'sym
          identity
          0
          #js {})))
    (when-not-advanced-mode
      (testing "dynamic get via js array"
        (is (= (oget+ sample-obj (make-selector-dynamically #js ["nested" "nested-key1"])) "nk1"))
        (is (= (oget+ sample-obj (make-selector-dynamically #js ["nested" :nested-key1])) "nk1"))))
    (when-not-advanced-mode
      (testing "object access validation should throw by default"
        (are [o msg] (thrown-with-msg? js/Error msg (oget o "key"))
          nil #"Unexpected object value \(nil\)"
          js/undefined #"Unexpected object value \(undefined\)"
          "s" #"Unexpected object value \(string\)"
          42 #"Unexpected object value \(number\)"
          true #"Unexpected object value \(boolean\)"
          false #"Unexpected object value \(boolean\)")
        (with-runtime-config {:error-reporting false}
          (under-phantom
            (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
              nil #"null is not an object"
              js/undefined #"undefined is not an object"))
          (are [o] (= (oget o "key") nil)
            "s"
            42
            true
            false))))
    (when-not-advanced-mode
      (testing "with {:error-reporting-mode :console} object access validation should report errors to console"
        (with-runtime-config {:error-reporting :console}
          (let [recorder (atom [""])
                expected-warnings "
ERROR: (\"Oops, Unexpected object value (nil)\" {:obj nil})
ERROR: (\"Oops, Unexpected object value (undefined)\" {:obj nil})
ERROR: (\"Oops, Unexpected object value (string)\" {:obj \"s\"})
ERROR: (\"Oops, Unexpected object value (number)\" {:obj 42})
ERROR: (\"Oops, Unexpected object value (boolean)\" {:obj true})
ERROR: (\"Oops, Unexpected object value (boolean)\" {:obj false})"]
            (with-console-recording recorder
              (are [o] (= (oget o "key") nil)
                nil
                js/undefined
                "s"
                42
                true
                false))
            (is (= (string/join "\n" @recorder) expected-warnings)))
          ; make sure we don't print multiple errors on subsequent missing keys...
          (let [recorder (atom [""])
                expected-warnings "
ERROR: (\"Oops, Unexpected object value (undefined)\" {:obj nil})"]
            (with-console-recording recorder
              (is (= (oget #js {:k1 #js {}} "k1" "k2" "k3") nil)))
            (is (= (string/join "\n" @recorder) expected-warnings))))))
    (when-not-advanced-mode
      (testing "with {:error-reporting-mode false} object access validation should be elided"
        (with-runtime-config {:error-reporting false}
          (under-phantom
            (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
              nil #"null is not an object"
              js/undefined #"undefined is not an object"))
          (are [o] (= (oget o "key") nil)
            "s"
            42
            true
            false))))
    (when-advanced-mode                                                                                                       ; advanced optimizations
      (testing "object access validation should crash or silently fail in advanced mode (no diagnostics)"
        (when-not-compiler-config {:key-get :goog}
                                  (are [o msg] (thrown-with-msg? js/TypeError msg (oget o "key"))
                                    nil #"null is not an object"
                                    js/undefined #"undefined is not an object")
                                  (are [o] (= (oget o "key") nil)
                                    "s"
                                    42
                                    true
                                    false))))
    (testing "static dot escaping"
      (let [o #js {".."     #js {".x." "."}
                   ".\\\\." "x"                                                                                               ; this is a cljs bug, it does java string escaping and then again when emitting javascript string
                   "prop.1" #js {".k2" "v2"
                                 "k3." #js {:some "val"}}}]
        (are [key expected] (= (oget o key) expected)
          "prop\\.1.\\.k2" "v2"
          "prop\\.1.k3\\..some" "val"
          "\\.\\..\\.x\\." "."
          "\\.\\\\." "x")))
    (testing "dynamic dot escaping"
      (let [o #js {".."     #js {".x." "."}
                   ".\\\\." "x"                                                                                               ; this is a cljs bug, it does java string escaping and then again when emitting javascript string
                   "prop.1" #js {".k2" "v2"
                                 "k3." #js {:some "val"}}}]
        (are [key expected] (= (oget+ o (identity key)) expected)
          "prop\\.1.\\.k2" "v2"
          "prop\\.1.k3\\..some" "val"
          "\\.\\..\\.x\\." "."
          "\\.\\\\." "x")))
    (when-not-advanced-mode
      (testing "dynamic empty selector access in oget"
        (let [recorder (atom [""])]
          (with-console-recording recorder
            (oget+ (js-obj) (identity nil))
            (oget+ (js-obj) (identity []))
            (oget+ (js-obj) (identity [[] []])))
          (is (= (string/join "\n" @recorder) "
WARN: (\"Oops, Accessing target object with empty selector\" nil)
WARN: (\"Oops, Accessing target object with empty selector\" nil)
WARN: (\"Oops, Accessing target object with empty selector\" nil)")))))
    (testing "oget corner cases"
      ; TODO
      )))

(deftest test-oset
  (testing "static set"
    (let [sample-obj #js {"nested" #js {}}]
      (are [selector] (= (oget (oset! sample-obj selector "val") selector) "val")
        "xxx"
        ["yyy"]
        ["nested" "y"])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"y\":\"val\"},\"xxx\":\"val\",\"yyy\":\"val\"}"))))
  (testing "dynamic selector set"
    (let [sample-obj #js {"nested" #js {}}
          dynamic-key-fn (fn [name] name)]
      (are [selector] (= (oget+ (oset!+ sample-obj selector "val") selector) "val")
        (dynamic-key-fn "key")
        [(dynamic-key-fn "nested") (dynamic-key-fn "key2")])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"key2\":\"val\"},\"key\":\"val\"}"))))
  (testing "static punching set!"
    (let [sample-obj #js {"nested" #js {}}]
      (are [selector] (= (oget+ (oset!+ sample-obj selector "val") selector) "val")
        ".!nested.!xxx"
        "!aaa"
        ["!z1" "!z2" "!z3"])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"xxx\":\"val\"},\"aaa\":\"val\",\"z1\":{\"z2\":{\"z3\":\"val\"}}}"))))
  (testing "dynamic punching set!"
    (let [sample-obj #js {"nested" #js {}}]
      (are [selector] (= (oget+ (oset!+ sample-obj (identity selector) "val") selector) "val")
        ".!nested.!xxx"
        "!aaa"
        ["!z1" "!z2" "!z3"])
      (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"xxx\":\"val\"},\"aaa\":\"val\",\"z1\":{\"z2\":{\"z3\":\"val\"}}}"))))
  (testing "punching set! with custom child-factory"
    (let [sample-obj #js {"nested" #js {}}
          counter (volatile! nil)]
      (with-child-factory (fn [_obj key]
                            (vreset! counter (str (if @counter (str @counter ",")) key))
                            (js-obj))
        (are [selector] (= (oget+ (oset!+ sample-obj selector "val") selector) "val")
          ".!nested.!xxx"
          "!aaa"
          ["!z1" "!z2" "z3"])
        (is (= @counter "z1,z2")))))                                                                                          ; only z1 and z2 are punched
  (testing "punching set! with :js-array child-factory"
    (let [sample-obj #js {"nested" #js {}}]
      (with-child-factory :js-array
        (are [selector] (= (oget+ (oset!+ sample-obj selector "val") selector) "val")
          ".!nested.!xxx.1"
          "!aaa"
          ["!z1" "!0" "3"])
        (is (= (js/JSON.stringify sample-obj) "{\"nested\":{\"xxx\":[null,\"val\"]},\"aaa\":\"val\",\"z1\":[[null,null,null,\"val\"]]}")))))
  (testing "flexible selector in oset!"
    (let [sample-obj #js {"n1" #js {"n2" #js {}}}]
      (is (= (oget (oset! sample-obj "n1" "n2" "val") "n1" "n2") "val"))
      (is (= (oget (oset! sample-obj ["n1" "n2"] "val") "n1" "n2") "val"))
      #_(is (= (oget (oset! sample-obj #js ["n1" "n2"] "val") "n1" "n2") "val"))))
  (testing "oset corner cases"
    ; TODO
    ))

(deftest test-ocall
  (testing "simple invocation via call"
    (let [counter (volatile! 0)
          sample-obj #js {"inc-fn"    #(vswap! counter inc)
                          "return-fn" (fn [& args] args)
                          "add-fn"    (fn [n] (vswap! counter + n))
                          "add*-fn"   (fn [& args] (vreset! counter (apply + @counter args)))}]
      (ocall sample-obj "inc-fn")                                                                                             ; note ocall should work the same as ocall!
      (is (= @counter 1))
      (is (= (ocall! sample-obj "return-fn" 1) '(1)))
      (is (= (ocall! sample-obj "return-fn") nil))
      (is (= (ocall! sample-obj "return-fn" 1 2 3) '(1 2 3)))
      (ocall! sample-obj "add-fn" 1)
      (is (= @counter 2))
      (ocall! sample-obj "add-fn" 1 2 3 4)
      (is (= @counter 3))
      (ocall! sample-obj "add*-fn" 1 2 3 4)
      (is (= @counter 13)))))

(deftest test-oapply
  (testing "simple invocation via apply"
    (let [counter (volatile! 0)
          sample-obj #js {"inc-fn"    #(vswap! counter inc)
                          "return-fn" (fn [& args] args)
                          "add-fn"    (fn [n] (vswap! counter + n))
                          "add*-fn"   (fn [& args] (vreset! counter (apply + @counter args)))}]
      (oapply sample-obj "inc-fn" [])                                                                                         ; note oapply should work the same as oapply!
      (is (= @counter 1))
      (is (= (oapply! sample-obj "return-fn" [1]) '(1)))
      (is (= (oapply! sample-obj "return-fn" []) nil))
      (is (= (oapply! sample-obj "return-fn" [1 2 3]) '(1 2 3)))
      (oapply! sample-obj "add-fn" (list 1))
      (is (= @counter 2))
      (oapply! sample-obj "add-fn" (list 1 2 3 4))
      (is (= @counter 3))
      (oapply! sample-obj "add*-fn" (range 5))
      (is (= @counter 13)))))
