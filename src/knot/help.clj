(ns knot.help
  "Help system: command registry (source of truth for parse + display)
   and renderers for top-level and per-command help."
  (:require [clojure.string :as str]
            [knot.output :as output]
            [knot.version :as version]))

(defn- bold
  "Bold `s` when `color?` is true; otherwise return `s` unchanged. Used
   for section/group headers."
  [color? s]
  (output/colorize color? [:bold] s))

(defn- cyan
  "Cyan `s` when `color?` is true; otherwise return `s` unchanged. Used
   for the things a user types: synopses, flag labels, example commands,
   and command lines in the top-level help."
  [color? s]
  (output/colorize color? [:cyan] s))

(defn- arg-token
  "Render a single :args entry as `<name>`, `[<name>]`, or `[<name>...]`."
  [{:keys [name required variadic]}]
  (cond
    variadic (str "[<" name ">...]")
    required (str "<" name ">")
    :else    (str "[<" name ">]")))

(defn synopsis
  "Compose a one-line synopsis: `knot <cmd> <arg> [<arg>] [<rest>...] [flags]`.
   `cmd-name` is the display string (`\"init\"`, `\"dep tree\"`); the trailing
   `[flags]` is appended only when the entry has at least one flag."
  [cmd-name {:keys [args flags]}]
  (let [head     (str "knot " cmd-name)
        arg-part (when (seq args)
                   (str " " (str/join " " (map arg-token args))))
        flag-part (when (seq flags) " [flags]")]
    (str head arg-part flag-part)))

(defn- flag-label
  "Render a flag as `--name` or `--name, -alias`."
  [{n :name a :alias}]
  (str "--" (clojure.core/name n)
       (when a (str ", -" (clojure.core/name a)))))

(defn- flags-block
  "Render a FLAGS section: header, then one line per flag with the label
   column padded to a uniform width and `desc` after a two-space gap.
   Returns nil when there are no flags so the caller can skip the section.
   Padding is computed from the uncolored label width — ANSI escapes
   carry no visual width but inflate `(count s)`, so we measure first
   and colorize last."
  [color? flags]
  (when (seq flags)
    (let [labels (map flag-label flags)
          max-w  (apply max (map count labels))]
      (str "\n" (bold color? "FLAGS") "\n"
           (str/join "\n"
                     (for [{:keys [desc] :as f} flags
                           :let [label (flag-label f)
                                 pad   (apply str (repeat (- max-w (count label)) \space))]]
                       (str "  " (cyan color? label) pad "  " (or desc ""))))
           "\n"))))

(defn- aliases-block
  "Render an ALIASES section listing each alias on its own indented line.
   Returns nil when `aliases` is empty/nil so the caller can omit the section."
  [color? aliases]
  (when (seq aliases)
    (str "\n" (bold color? "ALIASES") "\n"
         (str/join "\n"
                   (for [a aliases]
                     (str "  " (cyan color? a))))
         "\n")))

(defn- examples-block
  "Render an EXAMPLES section: each entry is `{:cmd ... :note ...}` printed
   as a two-line stanza (command indented two spaces, note indented four).
   Returns nil when there are no examples."
  [color? examples]
  (when (seq examples)
    (str "\n" (bold color? "EXAMPLES") "\n"
         (str/join "\n"
                   (for [{:keys [cmd note]} examples]
                     (str "  " (cyan color? cmd)
                          (when note (str "\n    " note)))))
         "\n")))

(defn key->cmd-name
  "Convert a registry key like `:init` or `:dep/tree` into its display
   form `\"init\"` or `\"dep tree\"`."
  [k]
  (if-let [parent (namespace k)]
    (str parent " " (name k))
    (name k)))

(defn- subcommands-block
  "Render a SUBCOMMANDS section for parent entries. `sub-keys` is a vector
   of registry keys (e.g. `[:dep/tree]`); each is resolved against
   `registry` to pick up its display name and description. Returns nil
   when there are no subcommands or no registry to resolve."
  [color? registry sub-keys]
  (when (and (seq sub-keys) registry)
    (let [resolved (for [k sub-keys
                         :let [entry (get registry k)]
                         :when entry]
                     {:cmd-name    (key->cmd-name k)
                      :description (:description entry)})
          labels   (map #(str "knot " (:cmd-name %)) resolved)
          max-w    (apply max 0 (map count labels))]
      (str "\n" (bold color? "SUBCOMMANDS") "\n"
           (str/join "\n"
                     (for [{:keys [cmd-name description]} resolved
                           :let [label (str "knot " cmd-name)
                                 pad   (apply str (repeat (- max-w (count label)) \space))]]
                       (str "  " (cyan color? label) pad "  " description)))
           "\n"))))

(def ^:private default-exit-codes
  "Convention for commands that don't override `:exit-codes` in the registry.
   Renders as the standard 0/1 line under EXIT CODES."
  [{:code 0 :when "on success"}
   {:code 1 :when "on error"}])

(defn- exit-codes-block
  "Render an EXIT CODES section. When `codes` is empty/nil, falls back to
   the standard 0/1 convention so every per-command page has the section."
  [color? codes]
  (let [codes (if (seq codes) codes default-exit-codes)]
    (str "\n" (bold color? "EXIT CODES") "\n"
         (str/join "\n"
                   (for [{:keys [code] reason :when} codes]
                     (str "  " code "  " reason)))
         "\n")))

(def registry
  "Source of truth for every CLI command. Keys are registry IDs:
   single-word commands use a bare keyword (`:init`); two-token
   subcommands use a namespaced keyword (`:dep/tree`). Each entry
   carries display fields (`:description`, `:flags[].desc`, `:examples`,
   `:exit-codes`) and parse fields (`:flags[].coerce/:alias`,
   `:restrict?`); `babashka.cli` specs are derived from the parse
   fields by `derive-spec`."
  {:init
   {:group       :project
    :description "Write .knot.edn stub and create the tickets dir."
    :args        []
    :flags       [{:name :prefix      :desc "Override the auto-derived ticket id prefix."}
                  {:name :tickets-dir :desc "Override the default tickets directory name."}
                  {:name :force :coerce :boolean
                   :desc "Overwrite an existing .knot.edn."}]
    :examples    [{:cmd "knot init"
                   :note "Create .knot.edn and .tickets/ in the current directory."}]}

   :prime
   {:group       :project
    :description "Emit a markdown primer for AI agent context-injection (project, in-progress, ready, recently-closed, commands)."
    :args        []
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit JSON instead of markdown."}
                  {:name :mode :desc "Filter all primer sections by mode (afk|hitl)."}
                  {:name :limit :coerce :long
                   :desc "Cap the number of ready-section tickets shown."}
                  {:name :status   :coerce [] :desc "Filter all sections by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter all sections by assignee (repeatable)."}
                  {:name :tag      :coerce [] :desc "Filter all sections by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter all sections by type (repeatable)."}]
    :examples    [{:cmd "knot prime"
                   :note "Print the markdown primer for the current project."}
                  {:cmd "knot prime --type bug --mode afk"
                   :note "Show only afk-mode bug tickets across all primer sections."}]
    :exit-codes  [{:code 0 :when "always (degrades to a no-project preamble)"}]}

   :create
   {:group       :lifecycle
    :description "Create a new ticket."
    :args        [{:name "title" :required true}]
    :restrict?   true
    :flags       [{:name :type        :alias :t :desc "Type label (default: task)."}
                  {:name :priority    :alias :p :coerce :long :desc "Priority 0-4 (default 2)."}
                  {:name :assignee    :alias :a :desc "Assignee handle."}
                  {:name :external-ref :coerce [] :desc "External reference (repeatable)."}
                  {:name :parent      :desc "Parent ticket id."}
                  {:name :tags        :desc "Comma-separated tag list."}
                  {:name :mode        :desc "Mode (afk|hitl)."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}
                  {:name :description :alias :d :body? true :desc "Body content for the Description section."}
                  {:name :design      :body? true :desc "Body content for the Design section."}
                  {:name :acceptance  :body? true :desc "Body content for the Acceptance Criteria section."}]
    :examples    [{:cmd "knot create \"Fix login bug\" -p 1 --tags auth,p0"
                   :note "Create a ticket at priority 1 with two tags."}]}

   :show
   {:group       :listing
    :description "Render the ticket with the given id."
    :args        [{:name "id" :required true}]
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of text."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI)."}]
    :examples    [{:cmd "knot show kno-01abc"
                   :note "Render the ticket whose id starts with 01abc."}]}

   :list
   {:group       :listing
    :aliases     ["ls"]
    :description "List live (non-terminal) tickets."
    :args        []
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI)."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable)."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}]
    :examples    [{:cmd "knot list --mode afk --tag p0"
                   :note "Show afk-mode tickets tagged p0."}]}

   :status
   {:group       :lifecycle
    :description "Transition a ticket to a new status."
    :args        [{:name "id" :required true} {:name "new-status" :required true}]
    :flags       [{:name :summary :desc "Closing summary (terminal transitions only)."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :examples    [{:cmd "knot status kno-01abc in_progress"
                   :note "Move a ticket into in_progress."}]}

   :start
   {:group       :lifecycle
    :description "Transition a ticket to the project's active status (default: in_progress)."
    :args        [{:name "id" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :examples    [{:cmd "knot start kno-01abc"
                   :note "Mark a ticket as active (in_progress by default)."}]}

   :close
   {:group       :lifecycle
    :description "Transition a ticket to the first terminal status."
    :args        [{:name "id" :required true}]
    :flags       [{:name :summary :desc "Closing summary recorded on the ticket."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope (with meta.archived_to) instead of the saved path."}]
    :examples    [{:cmd "knot close kno-01abc --summary \"Shipped in v1.2\""
                   :note "Close with a summary."}]}

   :reopen
   {:group       :lifecycle
    :description "Transition a ticket back to open."
    :args        [{:name "id" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :examples    [{:cmd "knot reopen kno-01abc"
                   :note "Reopen a closed ticket."}]}

   :dep
   {:group       :graph
    :description "Add <to> to <from>'s :deps (cycle-checked)."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (the from ticket post-mutation) instead of the saved path."}]
    :subcommands [:dep/tree]
    :examples    [{:cmd "knot dep kno-01abc kno-01def"
                   :note "Make kno-01abc depend on kno-01def."}]
    :exit-codes  [{:code 0 :when "edge saved"}
                  {:code 1 :when "cycle detected or unknown id"}]}

   :dep/tree
   {:group       :graph
    :description "Render the deps subtree (--full to expand duplicates)."
    :args        [{:name "id" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit JSON instead of text."}
                  {:name :full :coerce :boolean
                   :desc "Expand duplicate subtrees instead of marking them seen."}]
    :examples    [{:cmd "knot dep tree kno-01abc"
                   :note "Show what blocks kno-01abc."}]}

   :undep
   {:group       :graph
    :description "Remove <to> from <from>'s :deps."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (the from ticket post-mutation) instead of the saved path."}]
    :examples    [{:cmd "knot undep kno-01abc kno-01def"
                   :note "Drop the edge from kno-01abc to kno-01def."}]}

   :link
   {:group       :graph
    :description "Create symmetric :links across every pair of ids."
    :args        [{:name "a" :required true}
                  {:name "b" :required true}
                  {:name "rest" :variadic true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (array of touched tickets) instead of saved paths."}]
    :examples    [{:cmd "knot link kno-01abc kno-01def kno-01ghi"
                   :note "Link three tickets pairwise."}]}

   :unlink
   {:group       :graph
    :description "Remove the symmetric link between two ids."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (array of touched tickets) instead of saved paths."}]
    :examples    [{:cmd "knot unlink kno-01abc kno-01def"
                   :note "Drop the link between two tickets."}]}

   :ready
   {:group       :listing
    :description "List tickets whose deps are all closed."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI)."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable)."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}]
    :examples    [{:cmd "knot ready --mode afk"
                   :note "Show afk-mode tickets ready to start."}]}

   :blocked
   {:group       :listing
    :description "List tickets with at least one open dependency."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI)."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable)."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}]
    :examples    [{:cmd "knot blocked"
                   :note "Show tickets currently blocked by an open dep."}
                  {:cmd "knot blocked --mode afk"
                   :note "Show afk-mode blocked tickets."}]}

   :closed
   {:group       :listing
    :description "List terminal tickets, newest closed first."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI)."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable)."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}]
    :examples    [{:cmd "knot closed --limit 10"
                   :note "Show the ten most-recently-closed tickets."}
                  {:cmd "knot closed --type bug"
                   :note "Show closed bug tickets."}]}

   :add-note
   {:group       :notes
    :description "Append a timestamped note (text arg, stdin, or editor)."
    :args        [{:name "id" :required true}
                  {:name "text" :variadic true}]
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (the post-mutation ticket) instead of the saved path."}]
    :examples    [{:cmd "knot add-note kno-01abc \"Tested locally\""
                   :note "Append a one-line note."}
                  {:cmd "knot add-note kno-01abc"
                   :note "Open $EDITOR to compose a note interactively."}]
    :exit-codes  [{:code 0 :when "note saved or editor cancellation (empty)"}
                  {:code 1 :when "no ticket matches the id"}]}

   :edit
   {:group       :notes
    :description "Open the ticket file in $VISUAL/$EDITOR."
    :args        [{:name "id" :required true}]
    :flags       []
    :examples    [{:cmd "knot edit kno-01abc"
                   :note "Edit the ticket's frontmatter and body."}]}

   :update
   {:group       :notes
    :description "Apply non-interactive frontmatter and body updates to a ticket."
    :args        [{:name "id" :required true}]
    :flags       [{:name :title        :desc "Replace the title."}
                  {:name :type         :desc "Replace the type."}
                  {:name :priority     :coerce :long :desc "Replace the priority (0-4)."}
                  {:name :mode         :desc "Replace the mode (afk|hitl)."}
                  {:name :assignee     :desc "Set or clear (\"\") the assignee."}
                  {:name :parent       :desc "Set or clear (\"\") the parent id."}
                  {:name :tags         :desc "Replace tags (comma-list); pass \"\" to clear."}
                  {:name :external-ref :coerce []
                   :desc "Replace external_refs (repeatable). Pass a single \"\" to clear; omit entirely to leave alone."}
                  {:name :json :coerce :boolean
                   :desc "Emit a JSON envelope (the post-mutation ticket) instead of the saved path."}
                  {:name :description :alias :d :body? true
                   :desc "Replace the ## Description section."}
                  {:name :design       :body? true
                   :desc "Replace the ## Design section."}
                  {:name :acceptance   :body? true
                   :desc "Replace the ## Acceptance Criteria section."}
                  {:name :body         :body? true
                   :desc "Replace the whole body. Destructive (no --force); git is the documented undo path. Mutually exclusive with --description / --design / --acceptance."}]
    :examples    [{:cmd "knot update kno-01abc --priority 0 --tags p0,auth"
                   :note "Bump priority and replace the tag list."}
                  {:cmd "knot update kno-01abc --description \"New desc.\""
                   :note "Replace just the Description section."}
                  {:cmd "knot update kno-01abc --body \"Plain body.\""
                   :note "Destructive whole-body replace (use git to recover)."}]
    :exit-codes  [{:code 0 :when "ticket saved"}
                  {:code 1 :when "no ticket matches, ambiguous id, or conflicting body flags"}]}

   :info
   {:group       :project
    :description "Report effective runtime configuration and allowed values."
    :args        []
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of plain text."}
                  {:name :no-color :coerce :boolean
                   :desc "Accepted for consistency; info text is always plain (no ANSI)."}]
    :examples    [{:cmd "knot info"
                   :note "Print effective config and allowed values for the current project."}
                  {:cmd "knot info --json"
                   :note "Same payload, JSON envelope — for scripts and agents."}]
    :exit-codes  [{:code 0 :when "report emitted successfully"}
                  {:code 1 :when "no project found, invalid .knot.edn, or other failure"}]}

   :check
   {:group       :project
    :description "Validate project integrity (cycles, schema, dangling refs)."
    :args        [{:name "id" :variadic true}]
    :flags       [{:name :json     :coerce :boolean
                   :desc "Emit a JSON envelope instead of a text table."}
                  {:name :severity :coerce []
                   :desc "Filter by severity (error|warning, repeatable)."}
                  {:name :code     :coerce []
                   :desc "Filter by issue code (repeatable; unknown codes ok)."}]
    :examples    [{:cmd "knot check"
                   :note "Validate every ticket and config; exit 0/1/2."}
                  {:cmd "knot check kno-01abc kno-01def --json"
                   :note "Run per-ticket checks against just these ids; print JSON."}
                  {:cmd "knot check --code dep_cycle"
                   :note "Show only dep_cycle issues."}]
    :exit-codes  [{:code 0 :when "no errors in the filtered view"}
                  {:code 1 :when "one or more errors in the filtered view"}
                  {:code 2 :when "unable to scan (config invalid, no project root)"}]}})

(def ^:private group-order
  "Canonical group order and display headers. The renderer walks this
   list and emits a section per group that has at least one command."
  [[:project   "Project"]
   [:lifecycle "Lifecycle"]
   [:graph     "Graph"]
   [:listing   "Listing"]
   [:notes     "Notes"]])

(def command-order
  "Top-level command order for `top-level-help-text`. Subcommand keys
   (e.g. `:dep/tree`) are intentionally absent — they render indented
   beneath their parent via the parent's `:subcommands` field."
  [:init :prime :info :check
   :create :start :status :close :reopen
   :dep :undep :link :unlink
   :list :show :ready :blocked :closed
   :add-note :edit :update])

(defn- cmd-line-label
  "Render a top-level/subcommand line label: cmd-name + required positionals."
  [cmd-name args]
  (let [parts (concat [cmd-name]
                      (for [{:keys [name required]} args
                            :when required]
                        (str "<" name ">")))]
    (str/join " " parts)))

(defn- group-lines
  "Return the rendered lines for a single group: each top-level command
   followed by its subcommands (indented). All labels in the group are
   right-padded to a uniform width so descriptions align. Width math
   uses the uncolored label so ANSI escapes do not throw alignment off."
  [color? registry group-kw]
  (let [entries (for [k command-order
                      :let [entry (get registry k)]
                      :when (and entry (= group-kw (:group entry)))]
                  {:k k :entry entry})
        rows    (mapcat
                 (fn [{:keys [k entry]}]
                   (cons {:label  (cmd-line-label (key->cmd-name k) (:args entry))
                          :desc   (:description entry)
                          :indent 2}
                         (for [sk (:subcommands entry)
                               :let [sentry (get registry sk)]
                               :when sentry]
                           {:label  (cmd-line-label (key->cmd-name sk) (:args sentry))
                            :desc   (:description sentry)
                            :indent 4})))
                 entries)
        max-w   (apply max 0 (map #(+ (:indent %) (count (:label %))) rows))]
    (for [{:keys [label desc indent]} rows
          :let [pad (apply str (repeat (- max-w (count label) indent) \space))]]
      (str (apply str (repeat indent \space))
           (cyan color? label) pad "  " (or desc "")))))

(defn top-level-help-text
  "Render the grouped top-level help: USAGE banner, hint pointing at
   per-command help, then five group sections (Project, Lifecycle,
   Graph, Listing, Notes) listing the commands present in `registry`.
   `opts` may include `:color?` (bold for headers, cyan for command
   names)."
  [registry {:keys [color?]}]
  (let [groups (for [[g header] group-order
                     :let [lines (group-lines color? registry g)]
                     :when (seq lines)]
                 (str (bold color? header) "\n"
                      (str/join "\n" lines)))]
    (str (cyan color? (str "knot v" version/version)) "\n"
         "\n"
         (bold color? "USAGE") "\n  " (cyan color? "knot <command> [args...]") "\n"
         "\n"
         "Run `knot help <command>` for per-command details.\n"
         "\n"
         (str/join "\n\n" groups)
         "\n")))

(defn command-help-text
  "Render the per-command help page as plain text. `cmd-name` is the display
   name (`\"init\"`, `\"dep tree\"`). `entry` is a registry value. `opts`
   may include `:color?` (bold for section headers, cyan for synopsis /
   flag labels / example commands / subcommand labels) and `:registry`
   (used to resolve `:subcommands` keys to their display names +
   descriptions)."
  [cmd-name {:keys [description flags examples exit-codes subcommands aliases]
             :as entry}
   {:keys [color? registry] :as _opts}]
  (str (bold color? "USAGE") "\n  " (cyan color? (synopsis cmd-name entry)) "\n"
       (when description
         (str "\n" description "\n"))
       (aliases-block color? aliases)
       (flags-block color? flags)
       (subcommands-block color? registry subcommands)
       (examples-block color? examples)
       (exit-codes-block color? exit-codes)))

(defn resolve-key
  "Resolve a user-typed command name to a registry key. Direct registry
   keys win; otherwise scan entries for one whose `:aliases` contains
   `name`. Returns nil when no match. `name` is a string."
  [registry name]
  (let [direct (keyword name)]
    (if (contains? registry direct)
      direct
      (some (fn [[k entry]]
              (when (some #{name} (:aliases entry))
                k))
            registry))))

(defn derive-spec
  "Project a registry entry's :flags into a babashka.cli :spec map.
   Body-extracted flags (`:body? true`) are skipped — they are pulled
   from argv before babashka.cli sees them, so they must not appear
   in the parser spec. `:restrict? true` on the entry surfaces as
   `:restrict true` on the spec (rejects unknown flags loudly)."
  [{:keys [flags restrict?]}]
  (cond-> {:spec (into {}
                       (for [{:keys [name body?] :as flag} flags
                             :when (not body?)]
                         [name (dissoc flag :name :body? :desc)]))}
    restrict? (assoc :restrict true)))
