const TERM_OPTION_LABELS: Record<string, string> = {
  eq: '=',
  not: '!=',
  like: 'contains',
  nlike: 'excludes',
  gt: '>',
  gte: '>=',
  lt: '<',
  lte: '<=',
  in: 'in',
  nin: 'not in',
  btw: 'between',
  nbtw: 'not between',
}

type UseTermOptionsOptions = {
  pick?: string[]
}

export const useTermOptions = (options: UseTermOptionsOptions = {}) => {
  const picked = options.pick || []

  return {
    termOptions: picked.map((value) => ({
      label: TERM_OPTION_LABELS[value] || value,
      value,
    })),
  }
}
