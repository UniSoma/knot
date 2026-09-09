(ns knot.help
  "Help system: command registry (source of truth for parse + display)
   and renderers for top-level and per-command help."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [knot.listing :as listing]
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

(defn- notes-block
  "Render a NOTES section: one indented line per entry. Carries the
   caveats a flag description can't — the gotchas that would otherwise
   have to be cached in prose elsewhere. Returns nil when there are no
   notes."
  [color? notes]
  (when (seq notes)
    (str "\n" (bold color? "NOTES") "\n"
         (str/join "\n" (for [n notes] (str "  " n)))
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

(def ^:private listing-column-notes
  "NOTES shared by the `list`, `ready` and `blocked` registry entries: the
   live-induced preamble, AGE (a base column, not a computed one), then one
   line per `listing/columns` declaration in layout order — its `:header`
   and `:note`."
  (into ["Computed columns are live-induced: a closed ticket is neither counted nor conductive, so a chain running through one is severed."
         "AGE  time since the ticket's `updated` stamp, bucketed as Nd / Nw / Nm, or `-` when there is no usable stamp. No --json field of its own: read the raw `updated` timestamp."]
        (map (fn [{:keys [header note]}] (str header "  " note)))
        listing/columns))

(def topics
  "Bundled concept guides, printed verbatim by `knot help <topic>`. The
   same markdown is the agent skill under `resources/knot/skill/`:
   `intro` is its SKILL.md body, the rest its references. Insertion
   order is listing order; no topic name may shadow a command name,
   alias or subcommand, since the help dispatcher resolves those first."
  (array-map
   "intro"      {:resource "knot/skill/SKILL.md"
                 :summary  "What knot is, and the contract between the CLI, the tickets and .knot.edn"}
   "lifecycle"  {:resource "knot/skill/references/lifecycle.md"
                 :summary  "The acceptance and open-children gates, and the conditional claim"}
   "graph"      {:resource "knot/skill/references/graph.md"
                 :summary  "How far each listing filter reaches, and how to read the computed columns"}
   "json"       {:resource "knot/skill/references/json.md"
                 :summary  "The --json envelope, its per-command payloads and the error catalogue"}
   "autonomous" {:resource "knot/skill/references/autonomous.md"
                 :summary  "Modes as a contract, and the loop an unattended agent runs"}
   "writes"     {:resource "knot/skill/references/writes.md"
                 :summary  "Appending versus overwriting, and the sections a body may not hold"}))

(defn topic-text
  "The markdown for `topic`, or nil when it names no topic. Leading YAML
   frontmatter is dropped — SKILL.md carries a name/description block
   that means nothing on a terminal."
  [topic]
  (when-let [resource (get-in topics [topic :resource])]
    (-> (slurp (io/resource resource))
        (str/replace #"(?s)\A---\n.*?\n---\n" "")
        str/triml)))

(defn topics-list-text
  "Render `knot help topics`: one `name  summary` line per topic. Width
   math uses the uncolored name so ANSI escapes do not throw alignment
   off, as in the top-level command listing."
  [{:keys [color?]}]
  (let [max-w (apply max (map count (keys topics)))]
    (str/join "\n"
              (for [[topic {:keys [summary]}] topics
                    :let [pad (apply str (repeat (- max-w (count topic)) \space))]]
                (str "  " (cyan color? topic) pad "  " summary)))))

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
    :restrict?   true
    :flags       [{:name :prefix      :desc "Override the auto-derived ticket id prefix."}
                  {:name :tickets-dir :desc "Override the default tickets directory name."}
                  {:name :force :coerce :boolean
                   :desc "Overwrite an existing .knot.edn."}]
    :notes       ["Writing the config is all init does — install the agent skill separately with `knot skill install`."]
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
                  {:name :assignee :coerce [] :desc "Filter all sections by assignee (repeatable). Pass \"\" to match unassigned tickets."}
                  {:name :tag      :coerce [] :desc "Filter all sections by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter all sections by type (repeatable)."}
                  {:name :priority :coerce [:long] :desc "Filter all sections by priority 0-4 (repeatable)."}
                  {:name :parent   :coerce []
                   :desc "Filter to direct children of the given parent id (resolves partial ids; repeatable)."}]
    :notes       ["Unlike list/ready/blocked, an unresolvable --parent does not exit 1 — prime is wired to SessionStart and always exits 0, so it degrades to the no-project primer instead."
                  "The preamble closes by pointing at the installed agent skill, or at `knot help topics` when there is none; --json reports the same search as skill_installed and skill_dir."]
    :examples    [{:cmd "knot prime"
                   :note "Print the markdown primer for the current project."}
                  {:cmd "knot prime --type bug --mode afk"
                   :note "Show only afk-mode bug tickets across all primer sections."}
                  {:cmd "knot prime --parent kno-01abc"
                   :note "Scope every primer section to the direct children of an umbrella ticket."}]
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
                  {:name :description :alias :d :body? true
                   :desc (str "Body content for the Description section."
                              " The section ends at the next ## line: nest headings as ### or deeper, and write whole sections with update --body.")}
                  {:name :design      :body? true
                   :desc (str "Body content for the Design section."
                              " The section ends at the next ## line: nest headings as ### or deeper, and write whole sections with update --body.")}
                  {:name :acceptance  :coerce []
                   :desc "Acceptance criterion title (repeatable). Stored in frontmatter; rendered by `knot show`."}
                  {:name :dep :coerce []
                   :desc (str "Add a dep edge: the new ticket depends on <id> (repeatable). "
                              "Lenient on missing targets — unresolved ids are kept verbatim "
                              "as a forward ref.")}
                  {:name :link :coerce []
                   :desc (str "Add a symmetric link to <id> (repeatable). Strict: every target "
                              "must resolve uniquely, or the command fails before any write.")}]
    :examples    [{:cmd "knot create \"Fix login bug\" -p 1 --tags auth,p0"
                   :note "Create a ticket at priority 1 with two tags."}
                  {:cmd "knot create \"Refactor X\" --dep kno-01abc --link kno-01def"
                   :note "Wire the new ticket into the graph at create time."}]}

   :show
   {:group       :listing
    :description "Render the ticket with the given id."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of text."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI). Honors NO_COLOR env var."}]
    :notes       ["--json adds `sections` — the body split by `## ` heading slug (description, design, user-stories, notes, ...), preamble under \"\" — alongside the unchanged `body` string, plus `acceptance` as the structured [{title, done}] list. Take one section with `jq -r '.data.sections.design'` instead of re-reading the whole render."]
    :examples    [{:cmd "knot show kno-01abc"
                   :note "Render the ticket whose id starts with 01abc."}
                  {:cmd "knot show kno-01abc --json | jq -r '.data.sections.description'"
                   :note "Pull a single body section instead of the full render."}]}

   :list
   {:group       :listing
    :aliases     ["ls"]
    :description "List live (non-terminal) tickets."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI). Honors NO_COLOR env var."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable). Pass \"\" to match unassigned tickets."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}
                  {:name :priority :coerce [:long] :desc "Filter by priority 0-4 (repeatable)."}
                  {:name :parent   :coerce [] :desc "Filter to direct children of the given parent id (resolves partial ids; repeatable)."}
                  {:name :closure  :coerce [] :desc "Filter to tickets in the undirected transitive closure of the seed id(s) over parent, deps, and links (resolves partial ids; comma-separated or repeatable)."}
                  {:name :via      :coerce [] :desc "Restrict --closure to the listed axes (any of: parent, deps, links; comma-separated). Default: all three."}
                  {:name :component :coerce :string :desc "Filter to the seed id's live-induced connected component over parent, deps, and links (closed non-conductive; resolves a partial id; single id, never an ordinal). The CC column's action-companion. Mutually exclusive with --closure."}
                  {:name :acceptance-complete :coerce :boolean
                   :desc "Filter by acceptance completion. =false shows tickets with at least one undone AC; =true shows tickets where every AC is done. Tickets with no acceptance criteria are excluded."}]
    :notes       listing-column-notes
    :examples    [{:cmd "knot list --mode afk --tag p0"
                   :note "Show afk-mode tickets tagged p0."}
                  {:cmd "knot list --parent kno-01abc"
                   :note "Show the direct children of kno-01abc."}
                  {:cmd "knot list --closure kno-01abc --via parent,deps"
                   :note "Show live tickets related to kno-01abc through parent/deps edges."}
                  {:cmd "knot list --component kno-01abc"
                   :note "Show the live cluster (same CC island) that kno-01abc sits on."}
                  {:cmd "knot list --acceptance-complete=false"
                   :note "Show tickets with at least one undone acceptance criterion."}]}

   :status
   {:group       :lifecycle
    :description "Transition a ticket to a new status."
    :args        [{:name "id" :required true} {:name "new-status" :required true}]
    :restrict?   true
    :flags       [{:name :summary :desc "Closing summary (terminal transitions only)."}
                  {:name :force :coerce :boolean :default false
                   :desc "Bypass the acceptance and open-children gates. When a gate fires on an active→terminal transition, --summary is required (the override leaves a record); on *→active transitions, --summary is not required. With no gate to bypass, --force is a no-op."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :examples    [{:cmd "knot status kno-01abc in_progress"
                   :note "Move a ticket into in_progress."}
                  {:cmd "knot status kno-01abc closed --force --summary \"shipping anyway\""
                   :note "Bypass the acceptance gate (recorded as a Notes entry)."}]}

   :start
   {:group       :lifecycle
    :description "Transition a ticket to the project's active status (default: in_progress)."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :assignee :alias :a
                   :desc "Set the assignee as part of the transition (\"\" clears it)."}
                  {:name :if-unassigned :coerce :boolean
                   :desc "Only transition when the ticket has no assignee; otherwise write nothing and exit 1 (already_assigned)."}
                  {:name :force :coerce :boolean :default false
                   :desc "Bypass the open-children gate (no --summary required at start)."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :notes       ["--if-unassigned is the conditional claim: the assignee is read before any gate and before the write, so a losing claim leaves the file untouched."
                  "Any existing assignee loses the claim, including your own — two agents polling the same frontier cannot both win a ticket."
                  "Under --json the failure is {ok:false, error:{code:\"already_assigned\", current_assignee:\"<holder>\"}}; without it the message goes to stderr."]
    :examples    [{:cmd "knot start kno-01abc"
                   :note "Mark a ticket as active (in_progress by default)."}
                  {:cmd "knot start kno-01abc --assignee agent-1 --if-unassigned"
                   :note "Claim and start the ticket only if nobody holds it."}
                  {:cmd "knot start kno-01abc --force"
                   :note "Start the umbrella anyway despite open children."}]}

   :close
   {:group       :lifecycle
    :description "Transition a ticket to the first terminal status."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :summary :desc "Closing summary recorded on the ticket."}
                  {:name :external-ref :coerce []
                   :desc "Record an external reference alongside the ones already on the ticket (repeatable; idempotent). Never replaces. A blank value is rejected."}
                  {:name :force :coerce :boolean :default false
                   :desc "Bypass the acceptance and open-children gates; requires --summary when a gate fires. With no gate to bypass, --force is a no-op."}
                  {:name :json :coerce :boolean :desc "Emit a JSON envelope (with meta.archived_to) instead of the saved path."}]
    :notes       ["--external-ref appends here, unlike `knot update --external-ref`, which replaces the whole list. The status change, the --summary note and the ref land in one write."
                  "git:<sha> is the convention for the commit that closed the ticket. knot stores the string verbatim: it does not parse it, verify the sha, or read git HEAD for you."
                  "Re-recording a ref the ticket already carries is a no-op. Use `knot update --remove-external-ref` to take one back."]
    :examples    [{:cmd "knot close kno-01abc --summary \"Shipped in v1.2\""
                   :note "Close with a summary."}
                  {:cmd "knot close kno-01abc --summary \"Shipped in v1.2\" --external-ref git:9f2c1ab"
                   :note "Close and record the commit that did it, in one write."}
                  {:cmd "knot close kno-01abc --force --summary \"wontfix: outdated\""
                   :note "Override the acceptance gate when AC is intentionally unfinished."}]}

   :reopen
   {:group       :lifecycle
    :description "Transition a ticket back to open."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of the saved path."}]
    :examples    [{:cmd "knot reopen kno-01abc"
                   :note "Reopen a closed ticket."}]}

   :delete
   {:group       :lifecycle
    :description "Delete a ticket file (leaf-only by default; --cascade rewrites referrers)."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean
                   :desc "Emit a JSON envelope instead of the removed path."}
                  {:name :cascade :coerce :boolean
                   :desc "Rewrite every referrer (live + archive) to drop the target from :deps/:links and dissoc :parent before unlinking the file."}]
    :examples    [{:cmd "knot delete kno-01abc"
                   :note "Remove a leaf ticket (live or archive) from disk; refuses on incoming refs."}
                  {:cmd "knot delete kno-01abc --json"
                   :note "Same, JSON envelope; refusal emits has_incoming_refs."}
                  {:cmd "knot delete kno-01abc --cascade"
                   :note "Rewrite each referrer to drop the target, then delete."}]
    :exit-codes  [{:code 0 :when "file removed"}
                  {:code 1 :when "not found, ambiguous id, or incoming refs present (without --cascade)"}]}

   :dep
   {:group       :graph
    :description "Add <to> to <from>'s :deps (cycle-checked)."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :restrict?   true
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
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit JSON instead of text."}
                  {:name :full :coerce :boolean
                   :desc "Expand duplicate subtrees instead of marking them seen."}]
    :examples    [{:cmd "knot dep tree kno-01abc"
                   :note "Show what blocks kno-01abc."}]}

   :undep
   {:group       :graph
    :description "Remove <to> from <from>'s :deps."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (the from ticket post-mutation) instead of the saved path."}]
    :examples    [{:cmd "knot undep kno-01abc kno-01def"
                   :note "Drop the edge from kno-01abc to kno-01def."}]}

   :link
   {:group       :graph
    :description "Create symmetric :links across every pair of ids."
    :args        [{:name "a" :required true}
                  {:name "b" :required true}
                  {:name "rest" :variadic true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (array of touched tickets) instead of saved paths."}]
    :examples    [{:cmd "knot link kno-01abc kno-01def kno-01ghi"
                   :note "Link three tickets pairwise."}]}

   :unlink
   {:group       :graph
    :description "Remove the symmetric link between two ids."
    :args        [{:name "from" :required true} {:name "to" :required true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (array of touched tickets) instead of saved paths."}]
    :examples    [{:cmd "knot unlink kno-01abc kno-01def"
                   :note "Drop the link between two tickets."}]}

   :ready
   {:group       :listing
    :description "List tickets whose deps are all closed."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI). Honors NO_COLOR env var."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable). Pass \"\" to match unassigned tickets."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}
                  {:name :priority :coerce [:long] :desc "Filter by priority 0-4 (repeatable)."}
                  {:name :parent   :coerce [] :desc "Filter to direct children of the given parent id (resolves partial ids; repeatable)."}
                  {:name :closure  :coerce [] :desc "Filter to tickets in the undirected transitive closure of the seed id(s) over parent, deps, and links (resolves partial ids; comma-separated or repeatable)."}
                  {:name :via      :coerce [] :desc "Restrict --closure to the listed axes (any of: parent, deps, links; comma-separated). Default: all three."}
                  {:name :component :coerce :string :desc "Filter to the seed id's live-induced connected component over parent, deps, and links (closed non-conductive; resolves a partial id; single id, never an ordinal). The CC column's action-companion. Mutually exclusive with --closure."}
                  {:name :acceptance-complete :coerce :boolean
                   :desc "Filter by acceptance completion. =false shows tickets with at least one undone AC; =true shows tickets where every AC is done. Tickets with no acceptance criteria are excluded."}]
    :notes       listing-column-notes
    :examples    [{:cmd "knot ready --mode afk"
                   :note "Show afk-mode tickets ready to start."}
                  {:cmd "knot ready --parent kno-01abc"
                   :note "Show ready direct children of kno-01abc."}
                  {:cmd "knot ready --component kno-01abc"
                   :note "Show what in kno-01abc's live cluster is ready to start now."}]}

   :blocked
   {:group       :listing
    :description "List tickets with at least one open dependency."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI). Honors NO_COLOR env var."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable). Pass \"\" to match unassigned tickets."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}
                  {:name :priority :coerce [:long] :desc "Filter by priority 0-4 (repeatable)."}
                  {:name :parent   :coerce [] :desc "Filter to direct children of the given parent id (resolves partial ids; repeatable)."}
                  {:name :closure  :coerce [] :desc "Filter to tickets in the undirected transitive closure of the seed id(s) over parent, deps, and links (resolves partial ids; comma-separated or repeatable)."}
                  {:name :via      :coerce [] :desc "Restrict --closure to the listed axes (any of: parent, deps, links; comma-separated). Default: all three."}
                  {:name :component :coerce :string :desc "Filter to the seed id's live-induced connected component over parent, deps, and links (closed non-conductive; resolves a partial id; single id, never an ordinal). The CC column's action-companion. Mutually exclusive with --closure."}
                  {:name :acceptance-complete :coerce :boolean
                   :desc "Filter by acceptance completion. =false shows tickets with at least one undone AC; =true shows tickets where every AC is done. Tickets with no acceptance criteria are excluded."}]
    :notes       listing-column-notes
    :examples    [{:cmd "knot blocked"
                   :note "Show tickets currently blocked by an open dep."}
                  {:cmd "knot blocked --mode afk"
                   :note "Show afk-mode blocked tickets."}
                  {:cmd "knot blocked --parent kno-01abc"
                   :note "Show blocked direct children of kno-01abc."}
                  {:cmd "knot blocked --component kno-01abc"
                   :note "Show what in kno-01abc's live cluster is currently blocked."}]}

   :closed
   {:group       :listing
    :description "List terminal tickets, newest closed first."
    :args        []
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean :desc "Emit JSON instead of a table."}
                  {:name :no-color :coerce :boolean :desc "Force plain output (no ANSI). Honors NO_COLOR env var."}
                  {:name :limit    :coerce :long    :desc "Cap the number of rows."}
                  {:name :status   :coerce [] :desc "Filter by status (repeatable)."}
                  {:name :assignee :coerce [] :desc "Filter by assignee (repeatable). Pass \"\" to match unassigned tickets."}
                  {:name :tag      :coerce [] :desc "Filter by tag (repeatable)."}
                  {:name :type     :coerce [] :desc "Filter by type (repeatable)."}
                  {:name :mode     :coerce [] :desc "Filter by mode (repeatable)."}
                  {:name :priority :coerce [:long] :desc "Filter by priority 0-4 (repeatable)."}
                  {:name :parent   :coerce [] :desc "Filter to direct children of the given parent id (resolves partial ids; repeatable)."}
                  {:name :closure  :coerce [] :desc "Filter to tickets in the undirected transitive closure of the seed id(s) over parent, deps, and links (resolves partial ids; comma-separated or repeatable)."}
                  {:name :via      :coerce [] :desc "Restrict --closure to the listed axes (any of: parent, deps, links; comma-separated). Default: all three."}
                  {:name :acceptance-complete :coerce :boolean
                   :desc "Filter by acceptance completion. =false shows tickets with at least one undone AC; =true shows tickets where every AC is done. Tickets with no acceptance criteria are excluded."}]
    :examples    [{:cmd "knot closed --limit 10"
                   :note "Show the ten most-recently-closed tickets."}
                  {:cmd "knot closed --type bug"
                   :note "Show closed bug tickets."}
                  {:cmd "knot closed --parent kno-01abc"
                   :note "Show closed direct children of kno-01abc."}]}

   :add-note
   {:group       :notes
    :description "Append a timestamped note (text arg, stdin, or editor)."
    :args        [{:name "id" :required true}
                  {:name "text" :variadic true}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope (the post-mutation ticket) instead of the saved path."}]
    :notes       ["A note lands inside the ## Notes section, which ends at the next ## line — note text carrying a # or ## heading is refused. Nest headings as ### or deeper; write whole sections with knot update --body."]
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
    :restrict?   true
    :flags       []
    :examples    [{:cmd "knot edit kno-01abc"
                   :note "Edit the ticket's frontmatter and body."}]
    :notes       ["Needs a TTY. Without one — a CI job, an agent run — use `knot update` for frontmatter and named body sections, or `knot add-note` to append."]}

   :update
   {:group       :notes
    :description "Apply non-interactive frontmatter and body updates to a ticket."
    :args        [{:name "id" :required true}]
    :restrict?   true
    :flags       [{:name :title        :desc "Replace the title."}
                  {:name :type         :desc "Replace the type."}
                  {:name :status       :desc "Transition the status. Acceptance gate fires on active→terminal."}
                  {:name :summary      :desc "Closing summary recorded on the ticket (terminal transitions only)."}
                  {:name :force        :coerce :boolean :default false
                   :desc "Bypass the acceptance and open-children gates on a --status transition. When a gate fires on a terminal target, --summary is required; on active-status targets, --summary is not required. With no gate to bypass, --force is a no-op."}
                  {:name :priority     :coerce :long :desc "Replace the priority (0-4)."}
                  {:name :mode         :desc "Replace the mode (afk|hitl)."}
                  {:name :assignee     :desc "Set or clear (\"\") the assignee."}
                  {:name :if-unassigned :coerce :boolean
                   :desc "Only apply the update when the ticket has no assignee; otherwise write nothing and exit 1 (already_assigned)."}
                  {:name :parent       :desc "Set or clear (\"\") the parent id."}
                  {:name :tags         :desc "Replace tags (comma-list); pass \"\" to clear."}
                  {:name :add-tag      :coerce []
                   :desc "Add a single tag (repeatable; idempotent). Mutually exclusive with --tags."}
                  {:name :remove-tag   :coerce []
                   :desc "Remove a single tag (repeatable; idempotent). Mutually exclusive with --tags."}
                  {:name :external-ref :coerce []
                   :desc "Replace external_refs (repeatable). Pass a single \"\" to clear; omit entirely to leave alone."}
                  {:name :add-external-ref :coerce []
                   :desc "Add a single external reference (repeatable; idempotent). Mutually exclusive with --external-ref."}
                  {:name :remove-external-ref :coerce []
                   :desc "Remove a single external reference (repeatable; idempotent). Mutually exclusive with --external-ref."}
                  {:name :ac :coerce []
                   :desc "Acceptance criterion to flip, by 1-based ordinal or exact title (repeatable; all flips share one --done/--undone). Use --add-ac / --remove-ac to add or remove criteria."}
                  {:name :add-ac    :coerce []
                   :desc "Add an acceptance criterion with done: false (repeatable; idempotent on exact-match title)."}
                  {:name :remove-ac :coerce []
                   :desc "Remove an acceptance criterion by 1-based ordinal or exact title (repeatable). A value matching nothing exits 1."}
                  {:name :done   :coerce :boolean
                   :desc "Mark the --ac criterion as done."}
                  {:name :undone :coerce :boolean
                   :desc "Mark the --ac criterion as not done."}
                  {:name :json :coerce :boolean
                   :desc "Emit a JSON envelope (the post-mutation ticket) instead of the saved path."}
                  {:name :description :alias :d :body? true
                   :desc (str "Replace the ## Description section."
                              " The section ends at the next ## line: nest headings as ### or deeper, and replace whole sections with --body.")}
                  {:name :design       :body? true
                   :desc (str "Replace the ## Design section."
                              " The section ends at the next ## line: nest headings as ### or deeper, and replace whole sections with --body.")}
                  {:name :body         :body? true
                   :desc (str "Replace the whole body. Destructive (no --force); git is the documented undo path."
                              " Mutually exclusive with --description / --design."
                              " The five sections show renders from fields — ## Acceptance Criteria, ## Blockers,"
                              " ## Blocking, ## Children, ## Linked — are display-only and refused here:"
                              " if a frontmatter field holds it, the body doesn't."
                              " Use --add-ac / --remove-ac / --ac to mutate criteria.")}]
    :notes       ["--if-unassigned is the same conditional claim `knot start` offers: the assignee is read before any write, and a losing claim drops every other flag in the call."
                  "--ac and --remove-ac read an all-digits value as a 1-based ordinal into the acceptance list, the number `knot show` prints beside each criterion; anything else is an exact title. No prefix matching."
                  "Ordinals resolve against the list as it stands at that step of the apply order (add -> flip -> remove), so --add-ac \"new\" --ac <last> --done flips the criterion just added."
                  "A criterion whose title is all digits is reachable only by its own ordinal — the ordinal reading wins. Rename it to address it by name."]
    :examples    [{:cmd "knot update kno-01abc --priority 0 --tags p0,auth"
                   :note "Bump priority and replace the tag list."}
                  {:cmd "knot update kno-01abc --assignee agent-1 --if-unassigned"
                   :note "Claim the ticket only if nobody holds it."}
                  {:cmd "knot update kno-01abc --add-tag stale --remove-tag wip"
                   :note "Apply tag deltas (mutually exclusive with --tags)."}
                  {:cmd "knot update kno-01abc --add-external-ref git:9f2c1ab"
                   :note "Apply external-ref deltas (mutually exclusive with --external-ref)."}
                  {:cmd "knot update kno-01abc --description \"New desc.\""
                   :note "Replace just the Description section."}
                  {:cmd "knot update kno-01abc --ac \"Ship it\" --done"
                   :note "Flip the matching frontmatter acceptance criterion to done."}
                  {:cmd "knot update kno-01abc --ac 1 --ac 3 --done"
                   :note "Flip the first and third criteria by ordinal in one write."}
                  {:cmd "knot update kno-01abc --add-ac \"Ship it\" --remove-ac \"old\""
                   :note "Add and/or remove acceptance criteria by exact-match title (apply order: add → flip → remove)."}
                  {:cmd "knot update kno-01abc --body \"Plain body.\""
                   :note "Destructive whole-body replace (use git to recover)."}]
    :exit-codes  [{:code 0 :when "ticket saved"}
                  {:code 1 :when "no ticket matches, ambiguous id, conflicting body flags, or --if-unassigned lost the claim"}]}

   :info
   {:group       :project
    :description "Report effective runtime configuration and allowed values."
    :args        []
    :restrict?   true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of plain text."}
                  {:name :no-color :coerce :boolean
                   :desc "Accepted for consistency; info text is always plain (no ANSI). NO_COLOR is honored similarly."}]
    :examples    [{:cmd "knot info"
                   :note "Print effective config and allowed values for the current project."}
                  {:cmd "knot info --json"
                   :note "Same payload, JSON envelope — for scripts and agents."}]
    :exit-codes  [{:code 0 :when "report emitted successfully"}
                  {:code 1 :when "no project found, invalid .knot.edn, or other failure"}]}

   :migrate-ac
   {:group       :project
    :description "One-shot v0.3 migration: lift body `## Acceptance Criteria` sections into structured frontmatter."
    :args        []
    :restrict?   true
    :hidden?     true
    :flags       [{:name :json :coerce :boolean :desc "Emit a JSON envelope instead of plain text."}]
    :examples    [{:cmd "knot migrate-ac"
                   :note "Migrate every ticket; safe to re-run (idempotent)."}]
    :exit-codes  [{:code 0 :when "migration complete (or nothing to migrate)"}
                  {:code 1 :when "no project found, or another scan failure"}]}

   :check
   {:group       :project
    :description "Validate project integrity (cycles, schema, dangling refs)."
    :args        [{:name "id" :variadic true}]
    :restrict?   true
    :flags       [{:name :json     :coerce :boolean
                   :desc "Emit a JSON envelope instead of a text table."}
                  {:name :severity :coerce []
                   :desc "Filter by severity (error|warning, repeatable)."}
                  {:name :code     :coerce []
                   :desc "Filter by issue code (repeatable; unknown codes ok)."}]
    :notes       ["reserved_section is a warning: a body carries a ## Blockers, ## Blocking, ## Children or ## Linked heading, which knot show renders from the ticket's fields. Delete the section by hand — unlike legacy_acceptance_section there is no automatic fix, because the prose under a graph heading is usually narrative."
                  "duplicate_section is a warning: one body carries the same ## heading twice or more, usually from an old --description write that replaced only the first copy. Body sections concatenate rather than clobber, so nothing downstream shows the duplication. Keep one copy by hand with update --body or knot edit."]
    :examples    [{:cmd "knot check"
                   :note "Validate every ticket and config; exit 0/1/2."}
                  {:cmd "knot check kno-01abc kno-01def --json"
                   :note "Run per-ticket checks against just these ids; print JSON."}
                  {:cmd "knot check --code dep_cycle"
                   :note "Show only dep_cycle issues."}]
    :exit-codes  [{:code 0 :when "no errors in the filtered view"}
                  {:code 1 :when "one or more errors in the filtered view"}
                  {:code 2 :when "unable to scan (config invalid, no project root)"}]}

   :schema
   {:group       :project
    :description "Emit a JSON Schema document for ticket frontmatter, derived from the project's allowed values."
    :args        []
    :restrict?   true
    :flags       []
    :examples    [{:cmd "knot schema"
                   :note "Print the schema to stdout."}
                  {:cmd "knot schema > knot.schema.json"
                   :note "Write the checked-in schema file (or use `bb gen:schema`)."}]
    :exit-codes  [{:code 0 :when "schema emitted successfully"}
                  {:code 1 :when "no project found, invalid .knot.edn, or other failure"}]}

   :skill
   {:group       :project
    :description "Manage the bundled agent skill."
    :args        []
    :restrict?   true
    :flags       []
    :subcommands [:skill/install]
    :examples    [{:cmd "knot skill install"
                   :note "Install the bundled skill into this project."}]
    :exit-codes  [{:code 1 :when "no subcommand given — `skill` is a group; run a subcommand"}]}

   :skill/install
   {:group       :project
    :description "Write the bundled agent skill into a directory (default: .claude/skills/knot)."
    :args        [{:name "dir"}]
    :restrict?   true
    :flags       [{:name :json :coerce :boolean
                   :desc "Emit a JSON envelope ({dir, files}) instead of plain text."}]
    :notes       ["knot owns the files it writes: every install overwrites SKILL.md, references/ and agents/openai.yaml in the target directory without asking. Nothing else in that directory is touched, and no file is ever deleted."
                  "Without <dir>, the target is .knot.edn's :skill-dir (relative paths resolve from the project root, ~ expands), else <project-root>/.claude/skills/knot. An explicit <dir> always wins; when it differs from the effective :skill-dir, a stderr hint suggests recording it as :skill-dir so later installs default there."]
    :examples    [{:cmd "knot skill install"
                   :note "Install into .claude/skills/knot under the project root."}
                  {:cmd "knot skill install ~/.claude/skills/knot"
                   :note "Install once for every project on this machine."}
                  {:cmd "knot skill install --json"
                   :note "Same install, JSON envelope with the target dir and file list."}]
    :exit-codes  [{:code 0 :when "files written"}
                  {:code 1 :when "no project found (and no <dir> given), or a write failed"}]}

   :serve
   {:group       :project
    :description "Run the read-only Web UI on loopback (foreground; ctrl-c to stop)."
    :args        []
    :restrict?   true
    :flags       [{:name :port :coerce :long
                   :desc "Port to bind (default 7777; 0 = ephemeral, prints the assigned port)."}
                  {:name :open    :coerce :boolean
                   :desc "Open the panel in the system browser after binding (default when stdout is a tty)."}
                  {:name :no-open :coerce :boolean
                   :desc "Do not open the system browser. Overrides the tty-driven default."}
                  {:name :dev :coerce :boolean
                   :desc "Serve UI assets from resources/knot/serve/public on disk instead of the classpath (for local hacking on the front-end)."}]
    :examples    [{:cmd "knot serve"
                   :note "Bind 127.0.0.1:7777 and open the panel."}
                  {:cmd "knot serve --port 0 --no-open"
                   :note "Bind an ephemeral port and print the URL; do not open a browser."}]
    :exit-codes  [{:code 0 :when "server bound and shut down cleanly, or another knot serve was already running for this project"}
                  {:code 1 :when "port unavailable, no project found, or another startup failure"}]}})

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
  [:init :prime :info :check :schema :skill :serve
   :create :start :status :close :reopen :delete
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
         "Concept guides for the tracker itself: `knot help topics`.\n"
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
  [cmd-name {:keys [description flags examples exit-codes subcommands aliases notes]
             :as entry}
   {:keys [color? registry] :as _opts}]
  (str (bold color? "USAGE") "\n  " (cyan color? (synopsis cmd-name entry)) "\n"
       (when description
         (str "\n" description "\n"))
       (aliases-block color? aliases)
       (flags-block color? flags)
       (subcommands-block color? registry subcommands)
       (notes-block color? notes)
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
