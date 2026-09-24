import { graph as g } from '../graph.mjs';
const src = (path, lines) => [{ path, lines }];
const overview = (id, title, sources, nodes, edges) => {
  const graph = g(id, title, 'lr', title, sources, nodes, edges);
  graph.columns = ['Depot', 'ETL', 'PState', 'Query'].map((title, i) => ({ title,
    nodes: graph.nodes.filter(n => i === 0 ? n.kind === 'depot' : i === 1 ? n.kind === 'etl' : i === 2 ? n.kind === 'state' : ['query', 'external'].includes(n.kind)).map(n => n.id) }));
  return graph;
};
const profile = 'challenges/profile-module/test-resources/rama/gallery/profile_module.clj';
const rest = 'challenges/rest-api-integration-module/test-resources/rama/gallery/rest_api_integration_module.clj';
const music = 'challenges/music-catalog-migration/test-resources/rama/gallery/migrations_music_catalog_modules.clj';

export default {
  'profile-module': [
    overview('profile-overview', 'Profile registration, edits, and direct read', src(profile, '12-40'), `
      registrations | depot | *registration-depot hash(:username)
      edits | depot | *profile-edits-depot hash(:user-id)
      stream | etl | profiles stream
      owners | state | $$username->registration\nString → UUID + Long user ID
      ids | state | $$id\nModuleUniqueIdPState
      profiles | state | $$profiles\nLong → fixed-key profile fields
      reader | external | Client directly selects $$profiles[user-id]
    `, `
      registrations | stream | Consume Registration
      edits | stream | Consume ProfileEdits
      stream | owners | Read or update username registration
      stream | ids | Generate accepted registration ID
      ids | stream | Return generated ID
      stream | profiles | Hash generated ID and initialize profile
      stream | profiles | Expand edits and replace selected fields
      profiles | reader | Select profile directly
    `),
    g('profile-registration', 'Accept new registrations or matching UUIDs', 'td', 'An absent username or matching stored UUID enters the success path. Each accepted event generates a fresh ID and returns it. A different UUID has no acknowledgement path. Matching UUIDs do not guarantee a stable ID across appends.', src(profile, '23-37'), `
      request | event | Registration(username, UUID, pwd-hash)
      owner | state | $$username->registration[username]
      match | decision | Is the owner missing or UUID matching?
      reject | end | Conflicting UUID; no acknowledgement
      generate | etl | ModuleUniqueIdPState.genId
      save | state | Store UUID and fresh user ID
      profile | state | Hash ID; initialize username and pwd-hash
      ack | end | Return generated user ID
    `, `
      request | owner | Look up username owner
      owner | match | Check absence or UUID match
      match | reject | conflict
      match | generate | accepted
      generate | save | Generate fresh ID
      save | profile | Store profile by user ID
      profile | ack | Return ID
    `),
    g('profile-partitions', 'Username index and ID-keyed profile', 'partition', 'Registration ownership is username-hashed. Accepted registrations hash the generated user ID for profile state; edits and profiles use user ID. These are logical keys, not claims about placement.', src(profile, '12-20,26-40'), `
      registration | depot | *registration-depot hash(username)
      owner | state | $$username->registration\nusername → {user-id, uuid}
      ids | memory | $$id ModuleUniqueIdPState
      edits | depot | *profile-edits-depot hash(user-id)
      profile | state | $$profiles[user-id]\nusername, pwd-hash, display-name, height-inches
      route | route | Hash generated user ID
    `, `
      registration | owner | Key by username
      owner | ids | Generate ID for accepted registration
      ids | route | Key profile by generated ID
      route | profile | Initialize profile fields
      edits | profile | Update selected field by user ID
    `)
  ],
  'rest-api-integration-module': [
    overview('rest-overview', 'URL request, asynchronous GET, and response read', src(rest, '17-30'), `
      requests | depot | *get-depot hash(identity URL)
      getHttp | etl | get-http stream
      responses | state | $$responses\nString URL → response body
      reader | external | Client directly selects response by URL
    `, `
      requests | getHttp | Consume URL
      getHttp | responses | Start task-scoped async GET; store body by URL
      responses | reader | Select directly
    `),
    g('rest-completion', 'Store completed response bodies', 'td', 'The source extracts the body from NettyResponse without checking status. Transport exceptions fail processing and trigger retries; retry bounds and timeout behavior are unverified.', src(rest, '17-30'), `
      request | event | URL from depot
      future | etl | HTTP GET → CompletableFuture<NettyResponse>
      response | decision | Did the future return a response?
      body | etl | get-body(response); status unchecked
      state | state | $$responses[url] := body
      failure | end | Fail processing; retry bounds unverified
    `, `
      request | future | Start async GET
      future | response | Complete normally
      future | failure | Complete exceptionally
      response | body | Extract response body
      body | state | Overwrite response by URL
    `),
    g('rest-partitions', 'URL-keyed responses and task-scoped client', 'partition', 'The depot and response PState use URL identity. The task-global HTTP client has a separate prepare and close lifecycle.', src(rest, '8-14,20-30'), `
      request | depot | *get-depot hash(identity URL)
      task | memory | *http-client AsyncHttpClientTaskGlobal
      responses | state | $$responses[String URL] → String body
      lifecycle | external | prepareForTask creates; close disposes client
    `, `
      request | responses | Select response by URL
      task | lifecycle | Own client per task
      lifecycle | task | Prepare and close client
    `)
  ],
  'music-catalog-migration': [
    overview('catalog-overview', 'Album writes across catalog schema update', src(music, '7-40'), `
      albums | depot | *albums-depot hash(:artist)
      old | etl | Module A albums microbatch
      newer | etl | Module B albums microbatch
      catalog | state | $$albums\nartist → album name → album
      reader | external | Client selects by artist and album name
    `, `
      albums | old | Consume before update
      old | catalog | Write album with Vector<String> songs
      albums | newer | Consume after update
      newer | catalog | Parse incoming songs; write Vector<Song>
      catalog | reader | Select album directly
    `),
    g('catalog-migration', 'Migrate stored albums and parse new appends', 'td', 'Module B migrates old leaves only when the first song is a string; structured or empty values pass through. It parses every new append. Both paths preserve artist and album keys.', src(music, '19-40'), `
      prior | state | Existing album leaf: songs Vector<String>
      guard | decision | Is the first stored song a String?
      migrate | etl | Apply mapv parse-song to stored songs
      keep | etl | Keep structured or empty leaf unchanged
      incoming | event | New album append under Module B
      parse | etl | Parse every incoming song string
      value | state | Store album leaf with Vector<Song>
    `, `
      prior | guard | Read existing value during migration
      guard | migrate | yes
      guard | keep | no
      migrate | value | Convert old songs
      incoming | parse | Parse songs in every new append
      parse | value | Write in Module B schema
      keep | value | Preserve leaf
    `, ['The source parser splits on case-sensitive `ft` or `feat` markers, uses only the first two segments, trims comma-separated features, and drops empty tokens.']),
    g('catalog-partitions', 'Artist-owned albums with nested title keys', 'partition', 'Depot ingress hashes artist. PState nests artist → album-name → fixed-key leaf. Migration changes the song schema, not the keys.', src(music, '8-18,27-40'), `
      depot | depot | *albums-depot hash(:artist)
      catalog | state | $$albums\nMap<String artist, Map<String album-name, album leaf>>
      old | state | Module A leaf: name + Vector<String>
      newer | state | Module B leaf: name + Vector<Song>
      read | external | Direct select by artist and album name
    `, `
      depot | catalog | Key top-level subtree by artist
      catalog | old | old schema leaf
      catalog | newer | Preserve artist and title keys during migration
      newer | read | Select same nested album key
    `)
  ]
};
