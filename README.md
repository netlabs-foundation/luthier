Luthier
=======
Luthier is a library that provides ESB like features, allowing for quick definitions of data flows between different transports. It models accurately the different exchange patterns in endpoints, imposing no payload overhead, so that you are in charge for what is actually sent over.

Luthier has a different philosophy from the most prominent open ESBs becuase it prioritizes type safety and predictability of a flow outcome. To do this, we chose to create a type-safe domain language in a wider, general purpose language. This approach allows you to learn just the DSL and do a lot of stuff without knowing how to program in that language, but when things gets really complex, by learning a few things, you can still model them out elegantly.
