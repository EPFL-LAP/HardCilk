
struct Top : sc_module {
    SC_HAS_PROCESS(Top);

    Top(sc_module_name const& name = "")
        : sc_module { name }
        , SC_NAMED(generator0)
        , SC_NAMED(consumer0)
        , SC_NAMED(link0, sc_time(0.1, SC_SEC), sc_time(1.0, SC_SEC))
        , SC_NAMED(link1, sc_time(100, SC_SEC), sc_time(1.0, SC_SEC)) {

        connect_(generator0.sink, link0.source);
        connect_(link0.sink, link1.source);
        connect_(link1.sink, consumer0.source);

        SC_THREAD(generate);
        SC_THREAD(consume);
    }

private:
    Generator generator0;
    Consumer consumer0;
    Link link0;
    Link link1;

    Connect connect_;

    void generate() {
        for (int i = 0; i < 100; ++i) {
            auto packet = fmt::format("packet_{}", i);
            generator0.send(packet);
            fmt::println("sent '{}' at {:.2f}.", packet, sc_time_stamp().to_seconds());
        }
    }

    void consume() {
        while (true) {
            auto packet = consumer0.recv();
            fmt::println("received '{}' at {:.2f}.", packet, sc_time_stamp().to_seconds());
        }
    }
};