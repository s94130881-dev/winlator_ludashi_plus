/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*- */

#include <sys/mman.h>
#include <assert.h>
#include <string.h>
#include <unistd.h>
#include <sys/param.h>
#include <stdint.h>
#include <stddef.h>
#include <errno.h>

#include "tracee/tracee.h"
#include "tracee/reg.h"
#include "tracee/mem.h"
#include "syscall/sysnum.h"
#include "execve/execve.h"
#include "cli/note.h"

#include "compat.h"

/*
 * ============================================================
 *  Winlator / PRoot virtual heap configuration
 * ============================================================
 *
 * IMPORTANT:
 *
 * 26 GiB here means a MAXIMUM VIRTUAL HEAP SIZE.
 *
 * It does NOT create 26 GiB of physical DRAM.
 *
 * The Android kernel still decides how much real memory,
 * compressed RAM (ZRAM) and other backing resources are
 * actually available.
 *
 * 26 GiB = 26 * 1024^3
 */

#define GUEST_RAM_GIB              26ULL
#define GUEST_RAM_BYTES            (GUEST_RAM_GIB * 1024ULL * 1024ULL * 1024ULL)

/*
 * Safety limit.
 */
#define MAX_GUEST_HEAP_SIZE        GUEST_RAM_BYTES

/*
 * The first page is hidden so that a zero-sized brk heap can
 * still be represented by a real mmap mapping.
 */
static word_t heap_offset = 0;


/*
 * Align a size to the system page size.
 */
static size_t align_heap_size(size_t size)
{
	long page_size = sysconf(_SC_PAGE_SIZE);

	if (page_size <= 0)
		page_size = 4096;

	return (size_t)((size + (size_t)page_size - 1) &
			~((size_t)page_size - 1));
}


/*
 * Verify that a requested heap size is within the configured
 * 26 GiB virtual-memory limit.
 */
static bool valid_heap_size(size_t size)
{
	return size <= (size_t)MAX_GUEST_HEAP_SIZE;
}


/**
 * Put tracee's heap at a reliable location.
 */
void translate_brk_enter(Tracee *tracee)
{
	word_t new_brk_address;
	size_t old_heap_size;
	size_t new_heap_size;

	if (tracee->heap->disabled)
		return;

	if (heap_offset == 0) {
		heap_offset = sysconf(_SC_PAGE_SIZE);

		if ((int) heap_offset <= 0)
			heap_offset = 0x1000;
	}

	new_brk_address = peek_reg(tracee, CURRENT, SYSARG_1);


	/*
	 * ============================================================
	 * First heap allocation
	 * ============================================================
	 */
	if (tracee->heap->base == 0) {
		Sysnum sysnum;
		Mapping *mappings;
		Mapping *bss;

		if (new_brk_address != 0) {
			if (tracee->verbose > 0) {
				note(tracee,
				     WARNING,
				     INTERNAL,
				     "process %d is doing suspicious brk()",
				     tracee->pid);
			}

			return;
		}

		/*
		 * Put heap immediately after BSS.
		 */
		mappings = tracee->load_info->mappings;

		bss = &mappings[
			talloc_array_length(mappings) - 1
		];

		new_brk_address = bss->addr + bss->length;

#ifdef ARCH_ARM64
		sysnum = tracee->is_aarch32 ? PR_mmap2 : PR_mmap;
#else
		sysnum = PR_mmap2;
#endif

		set_sysnum(tracee, sysnum);

		poke_reg(
			tracee,
			SYSARG_1,
			new_brk_address
		);

		/*
		 * Only allocate one page initially.
		 *
		 * We do NOT allocate 26 GiB here.
		 */
		poke_reg(
			tracee,
			SYSARG_2,
			heap_offset
		);

		poke_reg(
			tracee,
			SYSARG_3,
			PROT_READ | PROT_WRITE
		);

		poke_reg(
			tracee,
			SYSARG_4,
			MAP_PRIVATE | MAP_ANONYMOUS
		);

		poke_reg(
			tracee,
			SYSARG_5,
			-1
		);

		poke_reg(
			tracee,
			SYSARG_6,
			0
		);

		return;
	}


	/*
	 * ============================================================
	 * Heap shrink / invalid address
	 * ============================================================
	 */
	if (new_brk_address < tracee->heap->base) {
		set_sysnum(tracee, PR_void);
		return;
	}


	/*
	 * ============================================================
	 * Calculate requested heap size
	 * ============================================================
	 */
	new_heap_size =
		(size_t)(new_brk_address - tracee->heap->base);

	old_heap_size =
		tracee->heap->size;


	/*
	 * Align to Android/Linux page size.
	 */
	new_heap_size =
		align_heap_size(new_heap_size);


	/*
	 * ============================================================
	 * 26 GiB limit
	 * ============================================================
	 */
	if (!valid_heap_size(new_heap_size)) {

		/*
		 * Return ENOMEM semantics to the tracee.
		 *
		 * We do NOT attempt to create a mapping larger
		 * than the configured 26 GiB virtual limit.
		 */
		set_sysnum(tracee, PR_void);

		return;
	}


	/*
	 * No resize needed.
	 */
	if (new_heap_size == old_heap_size) {

		set_sysnum(tracee, PR_void);

		return;
	}


	/*
	 * ============================================================
	 * Resize virtual heap
	 * ============================================================
	 *
	 * mremap() changes the size of the virtual mapping.
	 *
	 * It does not magically create physical DRAM.
	 */
	set_sysnum(tracee, PR_mremap);

	poke_reg(
		tracee,
		SYSARG_1,
		tracee->heap->base - heap_offset
	);

	poke_reg(
		tracee,
		SYSARG_2,
		old_heap_size + heap_offset
	);

	poke_reg(
		tracee,
		SYSARG_3,
		new_heap_size + heap_offset
	);

	poke_reg(
		tracee,
		SYSARG_4,
		0
	);

	poke_reg(
		tracee,
		SYSARG_5,
		0
	);
}


/**
 * Handle brk() result.
 */
void translate_brk_exit(Tracee *tracee)
{
	word_t result;
	word_t sysnum;
	int tracee_errno;

	if (tracee->heap->disabled)
		return;

	assert(heap_offset > 0);

	sysnum = get_sysnum(tracee, MODIFIED);

	result = peek_reg(
		tracee,
		CURRENT,
		SYSARG_RESULT
	);

	tracee_errno = (int) result;


	switch (sysnum) {

	/*
	 * ============================================================
	 * Internal no-op
	 * ============================================================
	 */
	case PR_void:

		poke_reg(
			tracee,
			SYSARG_RESULT,
			tracee->heap->base +
			tracee->heap->size
		);

		break;


	/*
	 * ============================================================
	 * Initial mmap()
	 * ============================================================
	 */
	case PR_mmap:
	case PR_mmap2:

		/*
		 * mmap() failure.
		 */
		if (tracee_errno < 0 &&
		    tracee_errno > -4096) {

			poke_reg(
				tracee,
				SYSARG_RESULT,
				0
			);

			break;
		}


		/*
		 * Store heap base.
		 */
		tracee->heap->base =
			result + heap_offset;

		tracee->heap->size = 0;


		poke_reg(
			tracee,
			SYSARG_RESULT,
			tracee->heap->base +
			tracee->heap->size
		);

		break;


	/*
	 * ============================================================
	 * mremap()
	 * ============================================================
	 */
	case PR_mremap:

		/*
		 * mremap() failed.
		 */
		if ((tracee_errno < 0 &&
		     tracee_errno > -4096) ||

		    (tracee->heap->base !=
		     result + heap_offset)) {

			poke_reg(
				tracee,
				SYSARG_RESULT,
				tracee->heap->base +
				tracee->heap->size
			);

			break;
		}


		/*
		 * Read the resulting size.
		 */
		{
			size_t requested_size;

			requested_size =
				(size_t)peek_reg(
					tracee,
					MODIFIED,
					SYSARG_3
				);


			/*
			 * Remove hidden first page.
			 */
			if (requested_size >= heap_offset)
				requested_size -= heap_offset;
			else
				requested_size = 0;


			/*
			 * Final safety check.
			 */
			if (requested_size >
			    (size_t)MAX_GUEST_HEAP_SIZE) {

				requested_size =
					(size_t)MAX_GUEST_HEAP_SIZE;
			}


			tracee->heap->size =
				requested_size;
		}


		poke_reg(
			tracee,
			SYSARG_RESULT,
			tracee->heap->base +
			tracee->heap->size
		);

		break;


	/*
	 * ============================================================
	 * Original brk()
	 * ============================================================
	 */
	case PR_brk:

		if (result ==
		    peek_reg(
			tracee,
			ORIGINAL,
			SYSARG_1)) {

			tracee->heap->disabled = true;
		}

		break;


	default:

		assert(0);

		break;
	}
}
