#include "hardCilkDriver.h"


hardCilkDriver::hardCilkDriver(Memory *memory)
{
    memory_ = memory;
    // sanityCheck();
}

hardCilkDriver::~hardCilkDriver()
{
}

/**
 * @brief Start the system by writing to the rPause registers of the different servers.
 * This MUST be called after init_system has been called.
 */
int hardCilkDriver::startSystem()
{

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + scheduler_server_rpause_shift, 0x0);
           
            // if(!taskDescriptor->isCont)
            //     memory_->writeReg64(*base_address + scheduler_server_processorInterrupt_shift, 0xFFFFFFFFF);
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + alloc_server_rpause_shift, 0x0);
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + mem_alloc_server_rpause_shift, 0x0);
        }
    }
    return 0;
}

int hardCilkDriver::waitPaused(uint64_t addr)
{
    while (memory_->readReg64(addr) == 0)
    {
        sleep(1);
    }
    return 0;
}

int hardCilkDriver::checkFinished()
{
    //sleep(4);
    int32_t finished = 0;
    //assert(return_addresses.size() > 0);
    
    for (auto addr : return_addresses)
    {
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&finished), addr, sizeof(finished));
        if (condition_(finished) == true)
        {
            printf("Found value %d at return address %lx\n", finished, addr);
            return 0; // Finished
        }

        //printf("Found value %d at return address %lx\n", finished, addr);
        // read sent and received message counts

        //usleep(5000); // Sleep for 0.5 seconds to avoid busy waiting
        // if(finished != past_value){
        //     past_value = finished;
        // } else {
        //     printf("No progress detected, assuming finished.\n");
        //     return 0;
        // }
        //return 0;
    }
    //usleep(5000);



    // auto messages_received_0 = memory_->readReg64(descriptor.getMfpgaBaseAddress() + 0x18);
    // auto messages_sent_0 = memory_->readReg64(descriptor.getMfpgaBaseAddress() + 0x20);
    // auto remoteStreamToMemCounts_0 = memory_->readReg64(descriptor.getMfpgaBaseAddress() + 0x30);
    // auto remoteStreamToMemCounts_1 = memory_->readReg64(descriptor.getMfpgaBaseAddress() + 0x38);

    // uint32_t remoteStreamToMemMasterDirectCount = (remoteStreamToMemCounts_0 >> 32);
    // uint32_t remoteStreamToMemSlaveDirectCount = (remoteStreamToMemCounts_0 & 0xFFFFFFFF);
    // uint32_t remoteStreamToMemSlaveDemuxedCount = (remoteStreamToMemCounts_1 >> 32);
    // uint32_t remoteStreamToMemSlaveTaskIdCount = (remoteStreamToMemCounts_1 & 0xFFFFFFFF);



    // // printf("    Messages received: %lu, Messages sent: %lu\n", messages_received_0, messages_sent_0);
    // // printf("    RemoteStreamToMem Master Direct Count %lu, Slave Direct Count %lu, Slave Demuxed Count %lu, Slave Task Id Count %lu\n", remoteStreamToMemMasterDirectCount, remoteStreamToMemSlaveDirectCount, remoteStreamToMemSlaveDemuxedCount, remoteStreamToMemSlaveTaskIdCount);

    // // Log the remote server registers
    // auto rs_task_page_rank_map_tasks_sent = memory_->readReg64(0xD0);
    // auto rs_task_page_rank_map_tasks_received = memory_->readReg64(0xD8);
    // auto rs_task_page_rank_map_requests_sent = memory_->readReg64(0xE0);
    // auto rs_task_page_rank_map_requests_accepted = memory_->readReg64(0xE8);
    // auto rs_task_page_rank_map_requests_forwarded = memory_->readReg64(0xF0);
    // auto rs_task_page_rank_map_self_requests_squished = memory_->readReg64(0xF8);
    // auto rs_task_page_rank_map_self_requests_reforwarded = memory_->readReg64(0x100);
    
    // auto rs_task_vertex_map_tasks_sent = memory_->readReg64(0x110);
    // auto rs_task_vertex_map_tasks_received = memory_->readReg64(0x118);
    // auto rs_task_vertex_map_requests_sent = memory_->readReg64(0x120);
    // auto rs_task_vertex_map_requests_accepted = memory_->readReg64(0x128);
    // auto rs_task_vertex_map_requests_forwarded = memory_->readReg64(0x130);
    // auto rs_task_vertex_map_self_requests_squished = memory_->readReg64(0x138);
    // auto rs_task_vertex_map_self_requests_reforwarded = memory_->readReg64(0x140);


    // auto rma_received_mem_req_count = memory_->readReg64(0x150);
    // auto rma_received_mem_ack_count = memory_->readReg64(0x158);
    // auto rma_sent_mem_req_count = memory_->readReg64(0x160);
    // auto rma_sent_mem_ack_count = memory_->readReg64(0x168);
    // auto rma_inFlightWrites = memory_->readReg64(0x170);
    // auto rma_receiveQueueSize = memory_->readReg64(0x178);
    // auto rma_readyValidIndicator = memory_->readReg64(0x180);

    



    // auto an_received_notifications_count = memory_->readReg64(0x190);
    // auto an_sent_notifications_count = memory_->readReg64(0x198);
    // auto an_ready_valid_indicator = memory_->readReg64(0x1A0);


    // auto read_valids_reg = memory_->readReg64(0x1D0 + 0x28);




    // // Log the bits representing each valid and ready
    
    // bool m_axis_tready            = (read_valids_reg >> 9) & 1;
    // bool m_axis_tvalid            = (read_valids_reg >> 8) & 1;
    // bool s_axis_tready            = (read_valids_reg >> 7) & 1;
    // bool s_axis_tvalid            = (read_valids_reg >> 6) & 1;
    // bool arg_ready                = (read_valids_reg >> 5) & 1;
    // bool arg_valid                = (read_valids_reg >> 4) & 1;
    // bool sched_ready              = (read_valids_reg >> 3) & 1;
    // bool sched_valid              = (read_valids_reg >> 2) & 1;
    // bool mem_ready                = (read_valids_reg >> 1) & 1;
    // bool mem_valid                = (read_valids_reg >> 0) & 1;


    // bool m_axis_ever_blocked = (read_valids_reg >> 14) & 1;
    // bool s_axis_ever_blocked = (read_valids_reg >> 13) & 1;
    // bool s_axis_mFPGA_argServers_ever_blocked = (read_valids_reg >> 12) & 1;
    // bool s_axis_mFPGA_schedulers_ever_blocked = (read_valids_reg >> 11) & 1;
    // bool s_axis_mFPGA_memAccess_ever_blocked = (read_valids_reg >> 10) & 1;

    // printf("Interface Ready/Valid Register = 0x%016lx\n", read_valids_reg);
    // printf(" m_axis_mFPGA        : TREADY=%d TVALID=%d\n", m_axis_tready, m_axis_tvalid);
    // printf(" s_axis_mFPGA        : TREADY=%d TVALID=%d\n", s_axis_tready, s_axis_tvalid);
    // printf(" argServers          : READY=%d  VALID=%d\n", arg_ready, arg_valid);
    // printf(" schedulers          : READY=%d  VALID=%d\n", sched_ready, sched_valid);
    // printf(" memAccess           : READY=%d  VALID=%d\n", mem_ready, mem_valid);

    // printf(" m_axis_ever_blocked : %d\n", m_axis_ever_blocked);
    // printf(" s_axis_ever_blocked : %d\n", s_axis_ever_blocked);
    // printf(" s_axis_mFPGA_argServers_ever_blocked : %d\n", s_axis_mFPGA_argServers_ever_blocked);
    // printf(" s_axis_mFPGA_schedulers_ever_blocked : %d\n", s_axis_mFPGA_schedulers_ever_blocked);
    // printf(" s_axis_mFPGA_memAccess_ever_blocked : %d\n", s_axis_mFPGA_memAccess_ever_blocked);





    // //Write all the registers to the terminal:
    // printf("Remote Server of task page rank map:\n");
    // printf("    Tasks sent: %lu, Tasks received: %lu\n", rs_task_page_rank_map_tasks_sent, rs_task_page_rank_map_tasks_received);
    // printf("    Requests sent: %lu, Requests accepted: %lu, Requests forwarded: %lu\n", rs_task_page_rank_map_requests_sent, rs_task_page_rank_map_requests_accepted, rs_task_page_rank_map_requests_forwarded);
    // printf("    Self requests squished: %lu, Self requests reforwarded: %lu\n", rs_task_page_rank_map_self_requests_squished, rs_task_page_rank_map_self_requests_reforwarded);

    // printf("Remote Server of task vertex map:\n");
    // printf("    Tasks sent: %lu, Tasks received: %lu\n", rs_task_vertex_map_tasks_sent, rs_task_vertex_map_tasks_received);
    // printf("    Requests sent: %lu, Requests accepted: %lu, Requests forwarded: %lu\n", rs_task_vertex_map_requests_sent, rs_task_vertex_map_requests_accepted, rs_task_vertex_map_requests_forwarded);
    // printf("    Self requests squished: %lu, Self requests reforwarded: %lu\n", rs_task_vertex_map_self_requests_squished, rs_task_vertex_map_self_requests_reforwarded);

    // printf("Argument Notifier:\n");
    // printf("    Received notifications: %lu, Sent notifications: %lu\n", an_received_notifications_count, an_sent_notifications_count);



    // printf("Remote memory access:\n");
    // printf("    Received memory requests: %lu, Received memory acks: %lu\n", rma_received_mem_req_count, rma_received_mem_ack_count);
    // printf("    Sent memory requests: %lu, Sent memory acks: %lu\n", rma_sent_mem_req_count, rma_sent_mem_ack_count);
    // printf("    In flight writes: %lu\n", rma_inFlightWrites);
    // printf("    Receive queue size: %lu\n", rma_receiveQueueSize);


    // printf(
    //     "MFPGA CONNECTIONS interfacesReadyReg = 0x%016llx\n"

    //     "arbiterToMfpgaNetwork.sources(0) : valid=%lu, ready=%lu\n"
    //     "arbiterToMfpgaNetwork.sources(1) : valid=%lu, ready=%lu\n"
    //     "arbiterToMfpgaNetwork.sources(2) : valid=%lu, ready=%lu\n"
    //     "arbiterToMfpgaNetwork.sources(3) : valid=%lu, ready=%lu\n"
    //     "arbiterToMfpgaNetwork.select : valid=%lu, ready=%lu\n"

    //     "m_axis_ever_blocked = %lu\n"
    //     "s_axis_ever_blocked = %lu\n"
    //     "s_axis_mFPGA_argServers_ever_blocked = %lu\n"
    //     "s_axis_mFPGA_schedulers_ever_blocked = %lu\n"
    //     "s_axis_mFPGA_memAccess_ever_blocked = %lu\n"

    //     "m_axis_mFPGA.get : valid=%lu, ready=%lu\n"
    //     "s_axis_mFPGA.get : valid=%lu, ready=%lu\n"
    //     "s_axis_mFPGA_argServers.get : valid=%lu, ready=%lu\n"
    //     "s_axis_mFPGA_schedulers.get : valid=%lu, ready=%lu\n"
    //     "s_axis_mFPGA_memAccess.get : valid=%lu, ready=%lu\n",

    //     read_valids_reg,

    //     /* arbiterToMfpgaNetwork.sources(0) */
    //     (read_valids_reg >> 15) & 1,
    //     (read_valids_reg >> 16) & 1,

    //     /* arbiterToMfpgaNetwork.sources(1) */
    //     (read_valids_reg >> 17) & 1,
    //     (read_valids_reg >> 18) & 1,

    //     /* arbiterToMfpgaNetwork.sources(2) */
    //     (read_valids_reg >> 19) & 1,
    //     (read_valids_reg >> 20) & 1,

    //     /* arbiterToMfpgaNetwork.sources(3) */
    //     (read_valids_reg >> 21) & 1,
    //     (read_valids_reg >> 22) & 1,

    //     /* arbiterToMfpgaNetwork.select */
    //     (read_valids_reg >> 23) & 1,
    //     (read_valids_reg >> 24) & 1,

    //     /* ever_blocked flags */
    //     (read_valids_reg >> 14) & 1,
    //     (read_valids_reg >> 13) & 1,
    //     (read_valids_reg >> 12) & 1,
    //     (read_valids_reg >> 11) & 1,
    //     (read_valids_reg >> 10) & 1,

    //     /* AXIS interfaces */
    //     (read_valids_reg >> 8) & 1,
    //     (read_valids_reg >> 9) & 1,

    //     (read_valids_reg >> 6) & 1,
    //     (read_valids_reg >> 7) & 1,

    //     (read_valids_reg >> 4) & 1,
    //     (read_valids_reg >> 5) & 1,

    //     (read_valids_reg >> 2) & 1,
    //     (read_valids_reg >> 3) & 1,

    //     (read_valids_reg >> 0) & 1,
    //     (read_valids_reg >> 1) & 1
    // );



    // printf(
    //     "readyValidIndicator (Remote Mem Access) = 0x%016llx\n"

    //     "write_response_sinks[7] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[6] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[5] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[4] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[3] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[2] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[1] : valid=%lu, ready=%lu\n"
    //     "write_response_sinks[0] : valid=%lu, ready=%lu\n"

    //     "local_write_modules_info.deq : valid=%lu, ready=%lu\n"
    //     "io.s_axis_remote : valid=%lu, ready=%lu\n"
    //     "io.m_axis_remote : valid=%lu, ready=%lu\n"
    //     "m_axi_mem.w : valid=%lu, ready=%lu\n"
    //     "m_axi_mem.r : valid=%lu, ready=%lu\n"
    //     "m_axi_mem.b : valid=%lu, ready=%lu\n"
    //     "m_axi_mem.aw : valid=%lu, ready=%lu\n"
    //     "m_axi_mem.ar : valid=%lu, ready=%lu\n"
    //     "local_modules_req_arbiter.select : valid=%lu, ready=%lu\n"
    //     "local_modules_req_arbiter.sink : valid=%lu, ready=%lu\n"
    //     "mfpga_master_arbiter.sources(0) : valid=%lu, ready=%lu\n"
    //     "mfpga_master_arbiter.sources(1) : valid=%lu, ready=%lu\n"
    //     "mfpga_master_arbiter.sources(2) : valid=%lu, ready=%lu\n"
    //     "write_responses : valid=%lu, ready=%lu\n"
    //     "read_responses : valid=%lu, ready=%lu\n"
    //     "read_requests : valid=%lu, ready=%lu\n"
    //     "write_requests : valid=%lu, ready=%lu\n"
    //     "receiveQueue.enq : valid=%lu, ready=%lu\n"
    //     "receiveQueue.deq : valid=%lu, ready=%lu\n",

    //     rma_readyValidIndicator,

    //     /* writeResponsesSinksRV [53:38] */
    //     (rma_readyValidIndicator >> 52) & 1,
    //     (rma_readyValidIndicator >> 53) & 1,
    //     (rma_readyValidIndicator >> 50) & 1,
    //     (rma_readyValidIndicator >> 51) & 1,
    //     (rma_readyValidIndicator >> 48) & 1,
    //     (rma_readyValidIndicator >> 49) & 1,
    //     (rma_readyValidIndicator >> 46) & 1,
    //     (rma_readyValidIndicator >> 47) & 1,
    //     (rma_readyValidIndicator >> 44) & 1,
    //     (rma_readyValidIndicator >> 45) & 1,
    //     (rma_readyValidIndicator >> 42) & 1,
    //     (rma_readyValidIndicator >> 43) & 1,
    //     (rma_readyValidIndicator >> 40) & 1,
    //     (rma_readyValidIndicator >> 41) & 1,
    //     (rma_readyValidIndicator >> 38) & 1,
    //     (rma_readyValidIndicator >> 39) & 1,

    //     /* local_write_modules_info.deq */
    //     (rma_readyValidIndicator >> 37) & 1,
    //     (rma_readyValidIndicator >> 36) & 1,

    //     /* io.s_axis_remote */
    //     (rma_readyValidIndicator >> 35) & 1,
    //     (rma_readyValidIndicator >> 34) & 1,

    //     /* io.m_axis_remote */
    //     (rma_readyValidIndicator >> 33) & 1,
    //     (rma_readyValidIndicator >> 32) & 1,

    //     /* m_axi_mem */
    //     (rma_readyValidIndicator >> 31) & 1,
    //     (rma_readyValidIndicator >> 30) & 1,
    //     (rma_readyValidIndicator >> 29) & 1,
    //     (rma_readyValidIndicator >> 28) & 1,
    //     (rma_readyValidIndicator >> 27) & 1,
    //     (rma_readyValidIndicator >> 26) & 1,
    //     (rma_readyValidIndicator >> 25) & 1,
    //     (rma_readyValidIndicator >> 24) & 1,
    //     (rma_readyValidIndicator >> 23) & 1,
    //     (rma_readyValidIndicator >> 22) & 1,

    //     /* arbiters */
    //     (rma_readyValidIndicator >> 21) & 1,
    //     (rma_readyValidIndicator >> 20) & 1,
    //     (rma_readyValidIndicator >> 19) & 1,
    //     (rma_readyValidIndicator >> 18) & 1,
    //     (rma_readyValidIndicator >> 17) & 1,
    //     (rma_readyValidIndicator >> 16) & 1,
    //     (rma_readyValidIndicator >> 15) & 1,
    //     (rma_readyValidIndicator >> 14) & 1,
    //     (rma_readyValidIndicator >> 13) & 1,
    //     (rma_readyValidIndicator >> 12) & 1,

    //     /* responses / requests / queues */
    //     (rma_readyValidIndicator >> 11) & 1,
    //     (rma_readyValidIndicator >> 10) & 1,
    //     (rma_readyValidIndicator >> 9) & 1,
    //     (rma_readyValidIndicator >> 8) & 1,
    //     (rma_readyValidIndicator >> 7) & 1,
    //     (rma_readyValidIndicator >> 6) & 1,
    //     (rma_readyValidIndicator >> 5) & 1,
    //     (rma_readyValidIndicator >> 4) & 1,
    //     (rma_readyValidIndicator >> 3) & 1,
    //     (rma_readyValidIndicator >> 2) & 1,
    //     (rma_readyValidIndicator >> 1) & 1,
    //     (rma_readyValidIndicator >> 0) & 1
    // );




    // printf(
    //     "readyValidIndicator (Argument Notifier) = 0x%08llx\n"
    //     "io.connNetwork : valid=%lu, ready=%lu\n"
    //     "remoteQSend.enq : valid=%lu, ready=%lu\n"
    //     "localQ.enq : valid=%lu, ready=%lu\n"
    //     "remoteQRec.deq : valid=%lu, ready=%lu\n"
    //     "localQ.deq : valid=%lu, ready=%lu\n"
    //     "arbiterToLocalServer.select : valid=%lu, ready=%lu\n"
    //     "arbiterToRemoteFPGA.select : valid=%lu, ready=%lu\n"
    //     "argument_notification.m_axis : valid=%lu, ready=%lu\n"
    //     "argument_notification.s_axis : valid=%lu, ready=%lu\n"
    //     "reset_send_counter.m_axis : valid=%lu, ready=%lu\n"
    //     "reset_send_counter.s_axis : valid=%lu, ready=%lu\n"
    //     "arbiterToRemoteFPGA.sink : valid=%lu, ready=%lu\n"
    //     "arbiterToLocalServer.sink : valid=%lu, ready=%lu\n",
    //     an_ready_valid_indicator,

    //     /* io.connNetwork */
    //     (an_ready_valid_indicator >> 25) & 1,
    //     (an_ready_valid_indicator >> 24) & 1,

    //     /* remoteQSend.enq */
    //     (an_ready_valid_indicator >> 23) & 1,
    //     (an_ready_valid_indicator >> 22) & 1,

    //     /* localQ.enq */
    //     (an_ready_valid_indicator >> 21) & 1,
    //     (an_ready_valid_indicator >> 20) & 1,

    //     /* remoteQRec.deq */
    //     (an_ready_valid_indicator >> 19) & 1,
    //     (an_ready_valid_indicator >> 18) & 1,

    //     /* localQ.deq */
    //     (an_ready_valid_indicator >> 17) & 1,
    //     (an_ready_valid_indicator >> 16) & 1,

    //     /* arbiterToLocalServer.select */
    //     (an_ready_valid_indicator >> 15) & 1,
    //     (an_ready_valid_indicator >> 14) & 1,

    //     /* arbiterToRemoteFPGA.select */
    //     (an_ready_valid_indicator >> 13) & 1,
    //     (an_ready_valid_indicator >> 12) & 1,

    //     /* argument_notification.m_axis */
    //     (an_ready_valid_indicator >> 11) & 1,
    //     (an_ready_valid_indicator >> 10) & 1,

    //     /* argument_notification.s_axis */
    //     (an_ready_valid_indicator >> 9) & 1,
    //     (an_ready_valid_indicator >> 8) & 1,

    //     /* reset_send_counter.m_axis */
    //     (an_ready_valid_indicator >> 7) & 1,
    //     (an_ready_valid_indicator >> 6) & 1,

    //     /* reset_send_counter.s_axis */
    //     (an_ready_valid_indicator >> 5) & 1,
    //     (an_ready_valid_indicator >> 4) & 1,

    //     /* arbiterToRemoteFPGA.sink */
    //     (an_ready_valid_indicator >> 3) & 1,
    //     (an_ready_valid_indicator >> 2) & 1,

    //     /* arbiterToLocalServer.sink */
    //     (an_ready_valid_indicator >> 1) & 1,
    //     (an_ready_valid_indicator >> 0) & 1
    // );
        

    // usleep(100000);
    // sleep(2);

    // if(return_addresses.size() == 0){
    //     auto hardware_queues =  memory_->readReg64(0x50 + 0x30);
    //     std::cout << "Hardware queues remaining: " << std::hex << hardware_queues << std::dec << std::endl;
    //     return 0;
    // }
    return 1; // Not Finished
}

int hardCilkDriver::managementLoop()
{
    // while (true)
    // {
        if (checkPaused() == 0)
        {
            managePausedServer();
        }
        if (checkFinished() == 0)
        {
            printf("Finished processing.\n");
            return 0;
        }
        return 1;
    //}
}

int hardCilkDriver::managePausedServer()
{
    // Which server of which task is paused?
    // Check the rPause registers of the different servers

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + scheduler_server_rpause_shift) != 0x0)
            {
                manageSchedulerServer(*base_address, *taskDescriptor);
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + alloc_server_rpause_shift) != 0x0)
            {
                manageAllocationServer(*base_address, *taskDescriptor);
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0)
            {
                manageMemoryAllocatorServer(*base_address, *taskDescriptor);
            }
        }
    }
    return 0;
}

/**
 *
 */

int hardCilkDriver::manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{
    // Read the rAddress of the scheduler server and the maxLength and write the data in the free memory
    uint64_t addr = memory_->readReg64(base_address + scheduler_server_raddr_shift);
    uint64_t maxLength = memory_->readReg64(base_address + scheduler_server_maxLength_shift);

    // Log the information of calling this function
    std::cout << "Managing scheduler server of task type " << taskDescriptor.name << " at address " << base_address << " with rAddress " << addr << " and maxLength " << maxLength << std::endl;

    //freed_mem_blocks.push_back(freedMemBlock{addr, maxLength * taskDescriptor.widthTask / 8}); // Free the memory and write it in bytes.

    // Allocate double the maxLength of the scheduler server
    uint64_t new_addr = memory_->allocateMemFPGA(2 * maxLength * taskDescriptor.widthTask / 8, taskDescriptor.widthTask / 8);

    // Read the data from the scheduler server to the cpu
    void *data = malloc(maxLength * taskDescriptor.widthTask / 8);
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(data), addr, maxLength * taskDescriptor.widthTask / 8);

    // Write the data to the new address
    memory_->copyToDevice(new_addr, reinterpret_cast<const uint8_t *>(data), maxLength * taskDescriptor.widthTask / 8);

    // Write the new address to the rAddress register
    memory_->writeReg64(base_address + scheduler_server_raddr_shift, new_addr);

    // Write the new head and tail registers
    memory_->writeReg64(base_address + scheduler_server_fifoTailReg_shift, maxLength);
    memory_->writeReg64(base_address + scheduler_server_fifoHeadReg_shift, 0x0);
    memory_->writeReg64(base_address + scheduler_server_currLen_shift, maxLength);

    // Write the new MaxLength
    memory_->writeReg64(base_address + scheduler_server_maxLength_shift, maxLength * 2);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + scheduler_server_rpause_shift, 0x0);

    return 0;
}

/**
 * @brief Manage the allocation server, this function is called when the allocation server is paused with zero entries available
 */

int hardCilkDriver::manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{
    // read the raddr of the server
    uint64_t addr = memory_->readReg64(base_address + alloc_server_raddr_shift);

    // Get the size of the queue from the taskDescriptor
    int size = taskDescriptor.getCapacityVirtualQueue("allocator");

    // Log the information of calling this function
    std::cout << "Managing allocation server of task type " << taskDescriptor.name << " at address " << base_address << " with rAddress " << addr << std::endl;

    std::vector<uint64_t> addresses;

    // Check the mapServerAddressToClosureBaseAddress and read the address as int and check it if set to 0x1000000 (indicating freed continuation task which is done by the argument notifier)
    for (auto address : taskDescriptor.mapServerAddressToClosureBaseAddress[base_address])
    {
        // read the whole memory block and check each address
        char *data = (char *)malloc(address.second * taskDescriptor.widthTask / 8);
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(data), address.first, address.second * taskDescriptor.widthTask / 8);

        // iterate over data and check if the value is less than 0
        for (int i = 0; i < address.second && addresses.size() < size; i++)
        {
            int val = *(int *)(data + i * taskDescriptor.widthTask / 8);
            if (val == 0x1000000)
            {
                // Indication of a freed closure, tagged from the ArgumentNotifier
                addresses.push_back(address.first + i * taskDescriptor.widthTask / 8);
            }
        }
    }

    // check the size of the addresses if less than size allocate memory to complete it
    if (addresses.size() < size)
    {
        int left_size = size - addresses.size();
        uint64_t continuation_tasks_holder_addr = memory_->allocateMemFPGA(left_size * taskDescriptor.widthTask / 8, taskDescriptor.widthTask / 8);
        taskDescriptor.mapServerAddressToClosureBaseAddress[base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, left_size));

        for (auto i = 0; i < left_size; i++)
        {
            addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.widthTask / 8);
        }
    }

    assert(addresses.size() == size);

    // Write the addresses to the continuation queue
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(addresses.data()), addresses.size() * sizeof(uint64_t));

    // Write the new addresses to the continuation queue
    memory_->writeReg64(base_address + alloc_server_availableSize_shift, size);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + alloc_server_rpause_shift, 0x0);

    return 0;
}

int hardCilkDriver::manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{ 
    // read the raddr of the server
    uint64_t addr = memory_->readReg64(base_address + mem_alloc_server_raddr_shift);

    // Get the size of the queue from the taskDescriptor
    uint64_t size = taskDescriptor.getCapacityVirtualQueue("memoryAllocator");

    // Log the information of calling this function
    std::cout << "Managing memory allocation server of task type " << taskDescriptor.name << " at address " << base_address << " with rAddress " << addr << std::endl;

    std::vector<uint64_t> addresses;


    // check the size of the addresses if less than size allocate memory to complete it
    if (addresses.size() < size)
    {
        int left_size = size - addresses.size();
        uint64_t continuation_tasks_holder_addr = memory_->allocateMemFPGA(left_size * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8, 512);

        std::vector<uint8_t> zeros(left_size * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8, 0);
        memory_->copyToDevice(continuation_tasks_holder_addr, zeros.data(), zeros.size());

        taskDescriptor.mapServerAddressToMallocBaseAddress[base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, left_size));

        for (auto i = 0; i < left_size; i++)
        {
            addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8);
        }
    }

    assert(addresses.size() == size);

    // Write the addresses to the continuation queue
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(addresses.data()), addresses.size() * sizeof(uint64_t));

    // Write the new addresses to the continuation queue
    memory_->writeReg64(base_address + mem_alloc_server_availableSize_shift, size);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + mem_alloc_server_rpause_shift, 0x0);

    return 0;
}


int hardCilkDriver::setReturnAddr(uint64_t addr)
{
    // Write zero to the return address using writeMem
    // uint64_t val = 0;

    // memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&val), sizeof(val));

    printf("NOTE: RETURN ADDRESS VALUE SHOULD BE SET BY THE USER CORRECTLY BASED ON ARGUMENT NOTIIFICATION\n");

    return_addresses.push_back(addr);

    // Log the return addresses
    printf("Return address set to 0x%lx\n", addr);

    return 0;
}

/**
 * @brief Check if the system is paused for management
 */
int hardCilkDriver::checkPaused()
{
    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + scheduler_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + alloc_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
    }
    return -1;
}

/**
 * Function that takes no params writes 0xDDDAAADDDD to the base_addr registers of each server
 * and reads it back. If the value is not the same, it throws an exception.
 *
 * It also creates a 100 element array of 0xDAAADDDDD and writes it to the memory and reads it back.
 * If the value is not the same, it throws an exception.
 */
int hardCilkDriver::sanityCheck()
{

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + scheduler_server_raddr_shift, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address + scheduler_server_raddr_shift) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for scheduler server at address " + std::to_string(*base_address));
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + alloc_server_raddr_shift, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address + alloc_server_raddr_shift) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for allocation server at address " + std::to_string(*base_address));
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for memory allocator server at address " + std::to_string(*base_address));
            }
        }
    }
    // Write a 100 element array of 0xDAAADDDDD to the memory and read it back
    uint64_t addr = memory_->allocateMemFPGA(100 * sizeof(uint64_t), sizeof(uint64_t));
    uint64_t data[100];
    for (int i = 0; i < 100; i++)
    {
        data[i] = 0xDAAADDDDD;
    }
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(data), 100 * sizeof(uint64_t));
    uint64_t read_data[100];
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(read_data), addr, 100 * sizeof(uint64_t));
    for (int i = 0; i < 100; i++)
    {
        if (read_data[i] != 0xDAAADDDDD)
        {
            throw std::runtime_error("Sanity check failed for memory at address " + std::to_string(addr));
        }
    }

    return 0;
}