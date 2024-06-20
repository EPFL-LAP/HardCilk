#include <d4e/interface.h>
#include <d4e/xil.h>

#include <stdio.h>
#include <stdlib.h>
#include <hardCilkDriver.h>
#include <qsort.h>

int main()
{
    hardCilkDriver d;
    
    qsortSpatialDescriptor qSortTask = create_qsort_task(&d, 100);
    std::vector<qsortSpatialDescriptor> q;
    q.push_back(qSortTask);
    
    d.init_system(q);
    d.start_system();
    
    d.management_loop();
    
    return 0;
}
