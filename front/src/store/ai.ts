import { defineStore } from 'pinia'

export const useAIStore = defineStore('ai', () => {
  const queryAgent = (_key: string, _params?: Record<string, any>) => {
    // stub – AI assistant feature not available in this build
  }

  const hideAiButton = () => {
    // stub – AI assistant feature not available in this build
  }

  return { queryAgent, hideAiButton }
})
